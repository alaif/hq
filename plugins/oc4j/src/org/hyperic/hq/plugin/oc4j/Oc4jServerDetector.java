/*
 * NOTE: This copyright does *not* cover user programs that use HQ
 * program services by normal system calls through the application
 * program interfaces provided as part of the Hyperic Plug-in Development
 * Kit or the Hyperic Client Development Kit - this is merely considered
 * normal use of the program, and does *not* fall under the heading of
 * "derived work".
 * 
 * Copyright (C) [2004-2007], Hyperic, Inc.
 * This file is part of HQ.
 * 
 * HQ is free software; you can redistribute it and/or modify
 * it under the terms version 2 of the GNU General Public License as
 * published by the Free Software Foundation. This program is distributed
 * in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307
 * USA.
 */

package org.hyperic.hq.plugin.oc4j;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.hyperic.hq.product.PluginException;
import org.hyperic.hq.product.ProductPlugin;
import org.hyperic.hq.product.ServerResource;
import org.hyperic.hq.product.jmx.MxServerDetector;
import org.hyperic.hq.product.jmx.MxUtil;

import org.hyperic.util.config.ConfigOption;
import org.hyperic.util.config.ConfigResponse;
import org.hyperic.util.config.ConfigSchema;

public class Oc4jServerDetector
extends MxServerDetector {

    private static final Log log =
        LogFactory.getLog(Oc4jServerDetector.class.getName());

    private static final String OC4J_JAR = "oc4j.jar";
    private static final String J2EE_HOME = "j2ee" + File.separator + "home";
    private static final String RMI_XML = J2EE_HOME + File.separator + "config" + File.separator + "rmi.xml";

    private static final String JMX_URL_BASE = "service:jmx:rmi://localhost:";
    private static final Pattern RMI_PORT_PATTERN = Pattern.compile("\\s*port\\s*=\"(.*)\".*");


    public List getServerResources(ConfigResponse platformConfig)
    throws PluginException {

        List servers = new ArrayList();
        List procs = getServerProcessList();

        for (int i=0; i<procs.size(); i++) {
            MxProcess process = (MxProcess)procs.get(i);
            String dir = process.getInstallPath();

            if (!isInstallTypeVersion(dir)) {
                continue;
            }

            // Create the server resource
            ServerResource server = createServerResource(dir);
            adjustClassPath(dir);

            ConfigResponse config = new ConfigResponse();
            ConfigSchema schema =
                getConfigSchema(getTypeInfo().getName(),
                        ProductPlugin.CFGTYPE_IDX_PRODUCT);

            if (schema != null) {
                ConfigOption option =
                    schema.getOption(PROP_PROCESS_QUERY);

                if (option != null) {
                    // Configure process.query
                    String query =
                        PROC_JAVA + ",Args.*.ct=" + OC4J_JAR;
                    config.setValue(option.getName(), query);
                }
            }

            if (process.getURL() != null) {
                config.setValue(MxUtil.PROP_JMX_URL,
                        process.getURL());
            }
            else {
                config.setValue(MxUtil.PROP_JMX_URL,
                        getJMXUrl(dir));
            }

            // default anything not auto-configured
            setProductConfig(server, config);

            String name =
                formatAutoInventoryName(server.getType(),
                        platformConfig,
                        server.getProductConfig(),
                        new ConfigResponse());

            if (name != null) {
                server.setName(name);
            }

            server.setMeasurementConfig();
            servers.add(server);
        }

        return servers;
    }


    // auto-detects the RMI port for the JMX URL
    private String getJMXUrl(String dir) {
        try {
            BufferedReader in = new BufferedReader(new FileReader(dir + File.separator + RMI_XML));
            String str;
            Matcher matcher;
            while ((str = in.readLine()) != null) {
                matcher = RMI_PORT_PATTERN.matcher(str);
                if (matcher.matches()) {
                    String port = matcher.group(1);
                    log.debug("Auto-detected RMI port to " + port);
                    return JMX_URL_BASE + port;
                }
            }
            in.close();
        } catch (IOException e) {
            log.error("Failed to detect RMI port in file " + RMI_XML, e);
        }
        return null;
    }


    protected List getServerProcessList()
    {
        List procs = new ArrayList();
        String procQuery = PROC_JAVA + ",Args.*.ct=" + OC4J_JAR;
        long[] pids = getPids(procQuery);
        log.debug(procQuery + " matched " + pids.length + " processes");

        for (int i=0; i<pids.length; i++) {
            long pid = pids[i];
            String[] args = getProcArgs(pid);
            String path = null;

            for (int j=1; j<args.length; j++) {
                String arg = args[j];
                // only match processes containing the "-jar */oc4j.jar" argument sequence 
                if (arg.endsWith(OC4J_JAR) && args[j-1].equals("-jar")) {
                    File jar = new File(arg);
                    if (jar.exists())
                        path = jar.getAbsolutePath();
                    else {
                        jar = new File(getProcCwd(pid) + File.separator + arg);
                        if (jar.exists())
                            path = jar.getAbsolutePath();
                    }
                    if (path != null) {
                        log.debug("Got path for oc4j.jar: " + path);;
                        int index = path.lastIndexOf(J2EE_HOME + File.separator + OC4J_JAR);
                        path = path.substring(0, index - 1);
                        break;
                    }
                }
            }

            if (path != null) {
                MxProcess process =
                    new Oc4jProcess(pid,
                            args,
                            path);    
                procs.add(process);
            }
        }

        return procs;
    }

    protected class Oc4jProcess extends MxProcess {

        protected Oc4jProcess(long pid, String[] args, String installpath) {
            super(pid, args, installpath);
        }
    }

}
