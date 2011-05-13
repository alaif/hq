/**
 * NOTE: This copyright does *not* cover user programs that use HQ
 * program services by normal system calls through the application
 * program interfaces provided as part of the Hyperic Plug-in Development
 * Kit or the Hyperic Client Development Kit - this is merely considered
 * normal use of the program, and does *not* fall under the heading of
 *  "derived work".
 *
 *  Copyright (C) [2004-2011], VMware, Inc.
 *  This file is part of HQ.
 *
 *  HQ is free software; you can redistribute it and/or modify
 *  it under the terms version 2 of the GNU General Public License as
 *  published by the Free Software Foundation. This program is distributed
 *  in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 *  even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 *  PARTICULAR PURPOSE. See the GNU General Public License for more
 *  details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, write to the Free Software
 *  Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307
 *  USA.
 */

package org.hyperic.hq.product.server.session;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Writer;
import java.net.JarURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;

import javax.annotation.PostConstruct;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.hyperic.hq.agent.server.session.AgentSynchronizer;
import org.hyperic.hq.appdef.Agent;
import org.hyperic.hq.appdef.server.session.AgentPluginStatus;
import org.hyperic.hq.appdef.server.session.AgentPluginStatusDAO;
import org.hyperic.hq.appdef.server.session.AgentPluginStatusEnum;
import org.hyperic.hq.appdef.server.session.AgentPluginSyncRestartThrottle;
import org.hyperic.hq.appdef.shared.AgentPluginUpdater;
import org.hyperic.hq.authz.server.session.AuthzSubject;
import org.hyperic.hq.authz.shared.AuthzSubjectManager;
import org.hyperic.hq.authz.shared.PermissionException;
import org.hyperic.hq.authz.shared.PermissionManager;
import org.hyperic.hq.authz.shared.ResourceManager;
import org.hyperic.hq.common.SystemException;
import org.hyperic.hq.context.Bootstrap;
import org.hyperic.hq.measurement.server.session.MonitorableType;
import org.hyperic.hq.measurement.server.session.MonitorableTypeDAO;
import org.hyperic.hq.product.Plugin;
import org.hyperic.hq.product.shared.PluginDeployException;
import org.hyperic.hq.product.shared.PluginManager;
import org.hyperic.hq.zevents.Zevent;
import org.hyperic.hq.zevents.ZeventListener;
import org.hyperic.hq.zevents.ZeventManager;
import org.hyperic.hq.zevents.ZeventPayload;
import org.hyperic.hq.zevents.ZeventSourceId;
import org.jdom.JDOMException;
import org.jdom.input.SAXBuilder;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.xml.sax.EntityResolver;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

@Service
@Transactional(readOnly=true)
public class PluginManagerImpl implements PluginManager, ApplicationContextAware {
    private static final Log log = LogFactory.getLog(PluginManagerImpl.class);

    private static final String TMP_DIR = System.getProperty("java.io.tmpdir");
    private static final String PLUGIN_DIR = "hq-plugins";
    private static final String AGENT_PLUGIN_DIR = "[/\\\\]pdk[/\\\\]plugins[/\\\\]";

    // used AtomicBoolean so that a groovy script may disable the mechanism live, no restarts
    private final AtomicBoolean isEnabled = new AtomicBoolean(true);
    
    private PermissionManager permissionManager;
    private AgentSynchronizer agentSynchronizer;
    private AgentPluginSyncRestartThrottle agentPluginSyncRestartThrottle;
    private PluginDAO pluginDAO;
    private AgentPluginStatusDAO agentPluginStatusDAO;
    private MonitorableTypeDAO monitorableTypeDAO;
    private ResourceManager resourceManager;

    private ApplicationContext ctx;

    private File customPluginDir;

    private AuthzSubjectManager authzSubjectManager;

    @Autowired
    public PluginManagerImpl(PluginDAO pluginDAO, AgentPluginStatusDAO agentPluginStatusDAO,
                             MonitorableTypeDAO monitorableTypeDAO,
                             PermissionManager permissionManager,
                             ResourceManager resourceManager,
                             AgentPluginSyncRestartThrottle agentPluginSyncRestartThrottle,
                             AgentSynchronizer agentSynchronizer,
                             AuthzSubjectManager authzSubjectManager) {
        this.pluginDAO = pluginDAO;
        this.agentPluginStatusDAO = agentPluginStatusDAO;
        this.monitorableTypeDAO = monitorableTypeDAO;
        this.permissionManager = permissionManager;
        this.agentPluginSyncRestartThrottle = agentPluginSyncRestartThrottle;
        this.agentSynchronizer = agentSynchronizer;
        this.resourceManager = resourceManager;
        this.authzSubjectManager = authzSubjectManager;
    }
    
    @PostConstruct
    public void postConstruct() {
        ZeventManager.getInstance().addBufferedListener(PluginFileRemoveZevent.class,
            new ZeventListener<PluginFileRemoveZevent>() {
                public void processEvents(List<PluginFileRemoveZevent> events) {
                    for (final PluginFileRemoveZevent event : events) {
                        deletePluginFiles(event.getPluginFileNames());
                    }
                }
            }
        );
    }
    
    public Plugin getByJarName(String jarName) {
        return pluginDAO.getByFilename(jarName);
    }
    
    @Transactional(readOnly=false)
    public void removePlugins(AuthzSubject subj, Collection<String> pluginFileNames)
    throws PluginDeployException {
        try {
            permissionManager.checkIsSuperUser(subj);
        } catch (PermissionException e) {
            throw new PluginDeployException("plugin.manager.deploy.super.user", e);
        }
        final Collection<Agent> agents = agentPluginStatusDAO.getAutoUpdatingAgents();
        final Map<String, Plugin> pluginMap = getPluginMap(pluginFileNames);
        removePluginsAndAssociatedResources(subj, new ArrayList<Plugin>(pluginMap.values()));
        final AgentPluginUpdater agentPluginUpdater = Bootstrap.getBean(AgentPluginUpdater.class);
        final Map<Integer, Collection<String>> toRemove = new HashMap<Integer, Collection<String>>(agents.size());
        for (final Agent agent : agents) {
            toRemove.put(agent.getId(), pluginFileNames);
        }
        agentPluginUpdater.queuePluginRemoval(toRemove);
        checkCanDeletePluginFiles(pluginFileNames);
        removePluginsWithoutAssociatedStatuses(pluginFileNames, pluginMap);
        ZeventManager.getInstance().enqueueEventAfterCommit(new PluginFileRemoveZevent(pluginFileNames));
    }

    @Transactional(readOnly=false, propagation=Propagation.REQUIRES_NEW)
    public void removeOrphanedPluginsInNewTran() throws PluginDeployException {
        final Collection<Plugin> plugins = agentPluginStatusDAO.getOrphanedPlugins();
        final boolean debug = log.isDebugEnabled();
        final Collection<String> pluginFileNames = new ArrayList<String>(plugins.size());
        for (final Plugin plugin : plugins) {
            if (debug) log.debug("removing orphaned plugin " + plugin);
            pluginFileNames.add(plugin.getPath());
        }
        final AuthzSubject overlord = authzSubjectManager.getOverlordPojo();
        removePlugins(overlord, pluginFileNames);
    }

    private void removePluginsWithoutAssociatedStatuses(Collection<String> pluginFileNames,
                                                        Map<String, Plugin> pluginMap) {
        final Map<String, Long> counts = agentPluginStatusDAO.getFileNameCounts(pluginFileNames);
        for (final String filename : pluginFileNames) {
            Long count = counts.get(filename);
            if (count == null || count <= 0) {
                final Plugin plugin = pluginMap.get(filename);
                pluginDAO.remove(plugin);
            }
        }
    }

    private Map<String, Plugin> getPluginMap(Collection<String> pluginFileNames) {
        final Collection<Plugin> plugins = pluginDAO.getPluginsByFileNames(pluginFileNames);
        final Map<String, Plugin> rtn = new HashMap<String, Plugin>(plugins.size());
        for (final Plugin plugin : plugins) {
            rtn.put(plugin.getPath(), plugin);
        }
        return rtn;
    }

    private void removePluginsAndAssociatedResources(AuthzSubject subj,
                                                     Collection<Plugin> plugins) {
        final long now = System.currentTimeMillis();
        for (final Plugin plugin : plugins) {
            if (plugin != null) {
                final Map<String, MonitorableType> map =
                    monitorableTypeDAO.findByPluginName(plugin.getName());
                resourceManager.removeResourcesAndTypes(subj, map.values());
                plugin.setDeleted(true);
                plugin.setModifiedTime(now);
            }
        }
    }

    @Value(value="${server.custom.plugin.dir}")
    public void setCustomPluginDir(String customPluginDir) {
        if (this.customPluginDir != null) {
            return;
        }
        if (customPluginDir.trim().isEmpty()) {
            File wdParent = new File(System.getProperty("user.dir")).getParentFile();
            this.customPluginDir = new File(wdParent, PLUGIN_DIR);
        } else {
            final File file = new File(customPluginDir);
            if (!file.exists()) {
                final boolean success = file.mkdirs();
                if (!success) {
                    throw new SystemException("cannot create custom plugin dir, " + customPluginDir +
                                              ", as defined in hq-server.conf");
                }
            } else if (!file.isDirectory()) {
                throw new SystemException("custom plugin dir, " + customPluginDir +
                                          ", defined in hq-server.conf is not a directory");
            }
            this.customPluginDir = file;
        }
    }

    public File getCustomPluginDir() {
        return customPluginDir;
    }

    public File getServerPluginDir() {
        try {
            return ctx.getResource("WEB-INF/" + PLUGIN_DIR).getFile();
        } catch (IOException e) {
            throw new SystemException(e);
        }
    }
    
    private void checkCanDeletePluginFiles(Collection<String> pluginFileNames)
    throws PluginDeployException {
        final File serverPluginDir = getServerPluginDir();
        final File customPluginDir = getCustomPluginDir();
        // Want this to be all or nothing, so first check if we can delete all the files
        for (final String filename : pluginFileNames) {
            final File customPlugin = new File(customPluginDir.getAbsolutePath() + "/" + filename);
            final File serverPlugin = new File(serverPluginDir.getAbsolutePath() + "/" + filename);
            if (!customPlugin.exists() && !serverPlugin.exists()) {
                String msg = "Could not remove plugin " + filename +
                             " from " + customPlugin.getAbsoluteFile() +
                             " or " + serverPlugin.getAbsoluteFile() + " file does not exist." +
                             "  Will ignore and continue with plugin removal";
                log.warn(msg);
            } else if (!canDelete(customPlugin) && !canDelete(serverPlugin)) {
                final String msg = "plugin.manager.delete.filesystem.perms";
                throw new PluginDeployException(
                    msg, filename, customPlugin.getAbsolutePath(), serverPlugin.getAbsolutePath());
            }
        }
    }

    private void deletePluginFiles(Collection<String> pluginFileNames) {
        final File serverPluginDir = getServerPluginDir();
        final File customPluginDir = getCustomPluginDir();
        for (final String filename : pluginFileNames) {
            final File customPlugin = new File(customPluginDir.getAbsolutePath() + "/" + filename);
            final File serverPlugin = new File(serverPluginDir.getAbsolutePath() + "/" + filename);
            customPlugin.delete();
            serverPlugin.delete();
        }
    }
    
    private boolean canDelete(File file) {
        if (!file.exists()) {
            return false;
        }
        // if a user does not have write perms to the dir or the file then they can't delete it
        if (!file.getParentFile().canWrite() && !file.canWrite()) {
            return false;
        }
        return true;
    }

    public Set<Integer> getAgentIdsInQueue() {
        final Set<Integer> rtn = new HashSet<Integer>();
        rtn.addAll(agentSynchronizer.getJobListByDescription(
            Arrays.asList(new String[]{AgentPluginUpdater.AGENT_PLUGIN_REMOVE,
                                       AgentPluginUpdater.AGENT_PLUGIN_TRANSFER})));
        rtn.addAll(agentPluginSyncRestartThrottle.getQueuedAgentIds());
        return rtn;
    }

    public Map<Integer, Long> getAgentIdsInRestartState() {
        return agentPluginSyncRestartThrottle.getAgentIdsInRestartState();
    }

    // XXX currently if one plugin validation fails all will fail.  Probably want to deploy the
    // plugins that are valid and return error status if any fail.
    public void deployPluginIfValid(AuthzSubject subj, Map<String, byte[]> pluginInfo)
    throws PluginDeployException {
        validatePluginFileNotInDeleteState(pluginInfo.keySet());
        final Collection<File> files = new ArrayList<File>();
        for (final Entry<String, byte[]> entry : pluginInfo.entrySet()) {
            final String filename = entry.getKey();
            final byte[] bytes = entry.getValue();
            File file = null;
            if (filename.toLowerCase().endsWith(".jar")) {
                file = getFileAndValidateJar(filename, bytes);
            } else if (filename.toLowerCase().endsWith(".xml")) {
                file = getFileAndValidateXML(filename, bytes);
            } else {
                throw new PluginDeployException("plugin.manager.bad.file.extension", filename);
            }
            files.add(file);
        }
        deployPlugins(files);
    }

    private void validatePluginFileNotInDeleteState(Collection<String> pluginFileNames)
    throws PluginDeployException {
        Collection<Plugin> plugins = pluginDAO.getPluginsByFileNames(pluginFileNames);
        for (Plugin plugin : plugins) {
            if (plugin == null) {
                continue;
            }
            if (plugin.isDeleted()) {
                throw new PluginDeployException("plugin.manager.plugin.is.deleted", plugin.getPath());
            }
        }
    }

    private File getFileAndValidateXML(String filename, byte[] bytes)
    throws PluginDeployException {
        FileWriter writer = null;
        File rtn = null;
        try {
            rtn = new File(TMP_DIR + File.separator + filename);
            final ByteArrayInputStream is = new ByteArrayInputStream(bytes);
            validatePluginXml(is);
            writer = new FileWriter(rtn);
            final String str = new String(bytes);
            writer.write(str);
            return rtn;
        } catch (JDOMException e) {
            if (rtn != null && rtn.exists()) {
                rtn.delete();
            }
            throw new PluginDeployException("plugin.manager.file.xml.wellformed.error", e, filename);
        } catch (IOException e) {
            if (rtn != null && rtn.exists()) {
                rtn.delete();
            }
            throw new PluginDeployException("plugin.manager.file.ioexception", e, filename);
        } finally {
            close(writer);
        }
    }

    private void deployPlugins(Collection<File> files) {
        final File pluginDir = getCustomPluginDir();
        if (!pluginDir.exists() && !pluginDir.isDirectory() && !pluginDir.mkdir()) {
            throw new SystemException(pluginDir.getAbsolutePath() +
                " does not exist or is not a directory");
        }
        for (final File file : files) {
            final File dest = new File(pluginDir.getAbsolutePath() + "/" + file.getName());
            file.renameTo(dest);
        }
    }

    private File getFileAndValidateJar(String filename, byte[] bytes) throws PluginDeployException {
        ByteArrayInputStream bais = null;
        JarInputStream jis = null;
        FileOutputStream fos = null;
        String file = null;
        String currXml = null;
        try {
            bais = new ByteArrayInputStream(bytes);
            jis = new JarInputStream(bais);
            final Manifest manifest = jis.getManifest();
            if (manifest == null) {
                throw new PluginDeployException("plugin.manager.jar.manifest.does.not.exist", filename);
            }
            file = TMP_DIR + File.separator + filename;
            fos = new FileOutputStream(file);
            fos.write(bytes);
            fos.flush();
            final File rtn = new File(file);
            final URL url = new URL("jar", "", "file:" + file + "!/");
            final JarURLConnection jarConn = (JarURLConnection) url.openConnection();
            final JarFile jarFile = jarConn.getJarFile();
            final Enumeration<JarEntry> entries = jarConn.getJarFile().entries();
            while (entries.hasMoreElements()) {
                InputStream is = null;
                try {
                    final JarEntry entry = entries.nextElement();
                    if (entry.isDirectory()) {
                        continue;
                    }
                    if (!entry.getName().toLowerCase().endsWith(".xml")) {
                        continue;
                    }
                    currXml = entry.getName();
                    is = jarFile.getInputStream(entry);
                    validatePluginXml(is);
                    currXml = null;
                } finally {
                    close(is);
                }
            }
            return rtn;
        } catch (IOException e) {
            final File toRemove = new File(file);
            if (toRemove != null && toRemove.exists()) {
                toRemove.delete();
            }
            throw new PluginDeployException("plugin.manager.file.ioexception", e, filename);
        } catch (JDOMException e) {
            final File toRemove = new File(file);
            if (toRemove != null && toRemove.exists()) {
                toRemove.delete();
            }
            throw new PluginDeployException("plugin.manager.file.xml.wellformed.error", e, currXml);
        } finally {
            close(jis);
            close(fos);
        }
    }
    
    private void validatePluginXml(InputStream is) throws JDOMException, IOException {
        SAXBuilder builder = new SAXBuilder();
        builder.setEntityResolver(new EntityResolver() {
            // systemId = file:///pdk/plugins/process-metrics.xml
            public InputSource resolveEntity(String publicId, String systemId)
            throws SAXException, IOException {
                final File entity = new File(systemId);
                final String filename = entity.getName().replaceAll(AGENT_PLUGIN_DIR, "");
                File file = new File(getCustomPluginDir(), filename);
                if (!file.exists()) {
                    file = new File(getServerPluginDir(), filename);
                }
                return (file.exists()) ?
                    new InputSource("file://" + file.getAbsolutePath()) :
                    new InputSource(systemId);
            }
        });
        builder.build(is);
    }

    public Map<Integer, Map<AgentPluginStatusEnum, Integer>> getPluginRollupStatus() {
        final Map<String, Plugin> pluginsByName = getAllPluginsByName();
        final List<AgentPluginStatus> statuses = agentPluginStatusDAO.findAll();
        final Map<Integer, Map<AgentPluginStatusEnum, Integer>> rtn =
            new HashMap<Integer, Map<AgentPluginStatusEnum, Integer>>();
        for (final AgentPluginStatus status : statuses) {
            if (status.getAgent().getPlatforms().isEmpty()) {
                continue;
            }
            final String name = status.getPluginName();
            final Plugin plugin = pluginsByName.get(name);
            if (plugin == null) {
                continue;
            }
            setPluginRollup(status, plugin.getId(), rtn);
        }
        return rtn;
    }
    
    private void setPluginRollup(AgentPluginStatus status, Integer pluginId,
                                 Map<Integer, Map<AgentPluginStatusEnum, Integer>> map) {
        Map<AgentPluginStatusEnum, Integer> tmp;
        if (null == (tmp = map.get(pluginId))) {
            tmp = new HashMap<AgentPluginStatusEnum, Integer>();
            tmp.put(AgentPluginStatusEnum.SYNC_FAILURE, 0);
            tmp.put(AgentPluginStatusEnum.SYNC_IN_PROGRESS, 0);
            tmp.put(AgentPluginStatusEnum.SYNC_SUCCESS, 0);
            map.put(pluginId, tmp);
        }
        final String lastSyncStatus = status.getLastSyncStatus();
        if (lastSyncStatus == null) {
            return;
        }
        final AgentPluginStatusEnum e = AgentPluginStatusEnum.valueOf(lastSyncStatus);
        tmp.put(e, tmp.get(e)+1);
    }
    
    public Plugin getPluginById(Integer id) {
        return pluginDAO.get(id);
    }
    
    @Transactional(readOnly=false)
    public void markDisabled(Collection<Integer> pluginIds) {
        final long now = System.currentTimeMillis();
        for (final Integer pluginId : pluginIds) {
            final Plugin plugin = pluginDAO.get(pluginId);
            if (plugin == null || plugin.isDeleted() || plugin.isDisabled()) {
                continue;
            }
            plugin.setDisabled(true);
            plugin.setModifiedTime(now);
        }
    }

    public Map<String, Integer> getAllPluginIdsByName() {
        final List<Plugin> plugins = pluginDAO.findAll();
        final Map<String, Integer> rtn = new HashMap<String, Integer>(plugins.size());
        for (final Plugin plugin : plugins) {
            rtn.put(plugin.getName(), plugin.getId());
        }
        return rtn;
    }

    private Map<String, Plugin> getAllPluginsByName() {
        final List<Plugin> plugins = pluginDAO.findAll();
        final Map<String, Plugin> rtn = new HashMap<String, Plugin>(plugins.size());
        for (final Plugin plugin : plugins) {
            rtn.put(plugin.getName(), plugin);
        }
        return rtn;
    }

    public Collection<AgentPluginStatus> getStatusesByPluginId(int pluginId, AgentPluginStatusEnum ... keys) {
        if (keys.length == 0) {
            return Collections.emptyList();
        }
        final Plugin plugin = pluginDAO.get(pluginId);
        if (plugin == null) {
            return Collections.emptyList();
        }
        return agentPluginStatusDAO.getPluginStatusByFileName(plugin.getPath(), Arrays.asList(keys));
    }
    
    public Map<Integer, AgentPluginStatus> getStatusesByAgentId(AgentPluginStatusEnum ... keys) {
        final Map<Integer, AgentPluginStatus> rtn = new HashMap<Integer, AgentPluginStatus>();
        final List<AgentPluginStatus> statuses = agentPluginStatusDAO.getPluginStatusByAgent(keys);
        for (final AgentPluginStatus status : statuses) {
            rtn.put(status.getAgent().getId(), status);
        }
        return rtn;
    }
    
    public boolean isPluginSyncEnabled() {
        return isEnabled.get();
    }
    
    @Value(value="${server.pluginsync.enabled}")
    public void setPluginSyncEnabled(boolean enabled) {
        isEnabled.set(enabled);
    }

    public Map<Plugin, Collection<AgentPluginStatus>> getOutOfSyncAgentsByPlugin() {
        return agentPluginStatusDAO.getOutOfSyncAgentsByPlugin();
    }

    public List<Plugin> getAllPlugins() {
        return pluginDAO.findAll();
    }

    public Collection<String> getOutOfSyncPluginNamesByAgentId(Integer agentId) {
        return agentPluginStatusDAO.getOutOfSyncPluginNamesByAgentId(agentId);
    }
     
    @Transactional(readOnly=false)
    public void updateAgentPluginSyncStatus(Integer agentId, AgentPluginStatusEnum from,
                                            AgentPluginStatusEnum to) {
        final Collection<Plugin> plugins = pluginDAO.findAll();
        final Map<String, AgentPluginStatus> statusMap =
            agentPluginStatusDAO.getStatusByAgentId(agentId);
        for (final Plugin plugin : plugins) {
            if (plugin == null || plugin.isDisabled()) {
                continue;
            }
            final AgentPluginStatus status = statusMap.get(plugin.getName());
            if (status == null || !status.getLastSyncStatus().equals(from.toString())) {
                continue;
            }
            status.setLastSyncStatus(to.toString());
        }
    }

    @Transactional(readOnly=false)
    public void updateAgentPluginSyncStatus(AgentPluginStatusEnum status,
                                            Map<Integer, Collection<Plugin>> agentToPlugins,
                                            Map<Integer, Collection<String>> agentToFileNames) {
        if (agentToPlugins == null) {
            agentToPlugins = Collections.emptyMap();
        }
        if (agentToFileNames == null) {
            agentToFileNames = Collections.emptyMap();
        }
        if (agentToPlugins.isEmpty() && agentToFileNames.isEmpty()) {
            return;
        }
        final long now = System.currentTimeMillis();
        final Set<Integer> agentIds = new HashSet<Integer>(agentToPlugins.keySet());
        agentIds.addAll(agentToFileNames.keySet());
        final Map<Integer, Map<String, AgentPluginStatus>> statusMap =
            agentPluginStatusDAO.getStatusByAgentIds(agentIds);
        for (final Entry<Integer, Collection<Plugin>> entry : agentToPlugins.entrySet()) {
            final Integer agentId = entry.getKey();
            final Map<String, AgentPluginStatus> map = statusMap.get(agentId);
            if (map == null) {
                continue;
            }
            final Collection<Plugin> plugins = entry.getValue();
            updateStatuses(agentId, plugins, map, now, status);
        }
        for (final Entry<Integer, Collection<String>> entry : agentToFileNames.entrySet()) {
            final Integer agentId = entry.getKey();
            final Map<String, AgentPluginStatus> map = statusMap.get(agentId);
            if (map == null) {
                continue;
            }
            final Collection<String> filenames = entry.getValue();
            final Collection<Plugin> plugins = pluginDAO.getPluginsByFileNames(filenames);
            updateStatuses(agentId, plugins, map, now, status);
        }
    }

    private void updateStatuses(Integer agentId, Collection<Plugin> plugins,
                                Map<String, AgentPluginStatus> map, long now,
                                AgentPluginStatusEnum s) {
        final String inProgress = AgentPluginStatusEnum.SYNC_IN_PROGRESS.toString();
        for (final Plugin plugin : plugins) {
            final AgentPluginStatus status = map.get(plugin.getName());
            if (status == null) {
                continue;
            }
            final String lastSyncStatus = status.getLastSyncStatus();
            if ((lastSyncStatus == null || !lastSyncStatus.equals(inProgress))
                    && s == AgentPluginStatusEnum.SYNC_IN_PROGRESS) {
                status.setLastSyncAttempt(now);
            }
            status.setLastSyncStatus(s.toString());
        }
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW, readOnly=false)
    public void updateAgentPluginSyncStatusInNewTran(AgentPluginStatusEnum s, Integer agentId,
                                                     Collection<Plugin> plugins) {
        final String inProgress = AgentPluginStatusEnum.SYNC_IN_PROGRESS.toString();
        if (plugins == null) {
            plugins = pluginDAO.findAll();
        }
        if (plugins.isEmpty()) {
            return;
        }
        final Map<String, AgentPluginStatus> statusMap =
            agentPluginStatusDAO.getStatusByAgentId(agentId);
        final long now = System.currentTimeMillis();
        for (final Plugin plugin : plugins) {
            if (plugin == null || plugin.isDisabled()) {
                continue;
            }
            final AgentPluginStatus status = statusMap.get(plugin.getName());
            if (status == null) {
                continue;
            }
            // only setLastSyncAttempt if it changes from !"in progress" to "in progress"
            if (!status.getLastSyncStatus().equals(inProgress)
                    && s == AgentPluginStatusEnum.SYNC_IN_PROGRESS) {
                status.setLastSyncAttempt(now);
            }
            status.setLastSyncStatus(s.toString());
        }
    }

    private void close(OutputStream os) {
        if (os == null) {
            return;
        }
        try {
            os.close();
        } catch (IOException e) {
            log.debug(e,e);
        }
    }

    private void close(Writer writer) {
        if (writer == null) {
            return;
        }
        try {
            writer.close();
        } catch (IOException e) {
            log.debug(e,e);
        }
    }

    private void close(InputStream is) {
        if (is == null) {
            return;
        }
        try {
            is.close();
        } catch (IOException e) {
            log.debug(e,e);
        }
    }

    @Transactional(readOnly=false)
    public void removeAgentPluginStatuses(Integer agentId, Collection<String> pluginFileNames) {
        agentPluginStatusDAO.removeAgentPluginStatuses(agentId, pluginFileNames);
    }

    @Transactional(readOnly=false)
    public void markDisabled(String pluginFileName) {
        final Plugin plugin = pluginDAO.getByFilename(pluginFileName);
        if (plugin == null || plugin.isDeleted()) {
            return;
        }
        if (!plugin.isDisabled()) {
            plugin.setDisabled(true);
            plugin.setModifiedTime(System.currentTimeMillis());
        }
    }

    public void setApplicationContext(ApplicationContext ctx) throws BeansException {
        this.ctx = ctx;
    }

    @Transactional(readOnly=false)
    public void markEnabled(String pluginName) {
        final Plugin plugin = pluginDAO.findByName(pluginName);
        if (plugin == null || plugin.isDeleted()) {
            return;
        }
        if (plugin.isDisabled()) {
            plugin.setDisabled(false);
            plugin.setModifiedTime(System.currentTimeMillis());
        }
    }
    
    private class PluginFileRemoveZevent extends Zevent {
        @SuppressWarnings("serial")
        private PluginFileRemoveZevent(Collection<String> pluginFileNames) {
            super(new ZeventSourceId() {}, new PluginFileRemovePayload(pluginFileNames));
        }
        private Collection<String> getPluginFileNames() {
            return ((PluginFileRemovePayload) getPayload()).getPluginFileNames();
        }
    }

    private class PluginFileRemovePayload implements ZeventPayload {
        private final Collection<String> pluginFileNames;
        private PluginFileRemovePayload(Collection<String> pluginFileNames) {
            this.pluginFileNames = pluginFileNames;
        }
        private Collection<String> getPluginFileNames() {
            return pluginFileNames;
        }
    }

}
