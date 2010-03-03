/*
 * NOTE: This copyright does *not* cover user programs that use HQ
 * program services by normal system calls through the application
 * program interfaces provided as part of the Hyperic Plug-in Development
 * Kit or the Hyperic Client Development Kit - this is merely considered
 * normal use of the program, and does *not* fall under the heading of
 * "derived work".
 *
 * Copyright (C) [2004-2009], Hyperic, Inc.
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

package org.hyperic.hq.appdef.server.session;

import java.util.Collection;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.hyperic.hq.appdef.ConfigResponseDB;
import org.hyperic.hq.appdef.Ip;
import org.hyperic.hq.appdef.shared.AppdefEntityConstants;
import org.hyperic.hq.appdef.shared.AppdefEntityID;
import org.hyperic.hq.appdef.shared.AppdefEntityNotFoundException;
import org.hyperic.hq.appdef.shared.ConfigFetchException;
import org.hyperic.hq.appdef.shared.ConfigManager;
import org.hyperic.hq.appdef.shared.PlatformNotFoundException;
import org.hyperic.hq.appdef.shared.ServerNotFoundException;
import org.hyperic.hq.appdef.shared.ServiceNotFoundException;
import org.hyperic.hq.authz.server.session.AuthzSubject;
import org.hyperic.hq.authz.shared.PermissionException;
import org.hyperic.hq.autoinventory.AICompare;
import org.hyperic.hq.product.ProductPlugin;
import org.hyperic.hq.zevents.ZeventEnqueuer;
import org.hyperic.util.config.ConfigResponse;
import org.hyperic.util.config.EncodingException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 */
@Service
public class ConfigManagerImpl implements ConfigManager {
    private static final int MAX_VALIDATION_ERR_LEN = 512;
    protected final Log log = LogFactory.getLog(ConfigManagerImpl.class.getName());
    private ConfigResponseDAO configResponseDAO;
    private ZeventEnqueuer zeventManager;
    private ServiceDAO serviceDAO;
    private ServerDAO serverDAO;
    private PlatformDAO platformDAO;

    @Autowired
    public ConfigManagerImpl(ConfigResponseDAO configResponseDAO, ZeventEnqueuer zeventManager, ServiceDAO serviceDAO,
                             ServerDAO serverDAO, PlatformDAO platformDAO) {
        this.configResponseDAO = configResponseDAO;
        this.zeventManager = zeventManager;
        this.serviceDAO = serviceDAO;
        this.serverDAO = serverDAO;
        this.platformDAO = platformDAO;
    }

    /**
     * 
     */
    public ConfigResponseDB createConfigResponse(byte[] productResponse, byte[] measResponse, byte[] controlResponse,
                                                 byte[] rtResponse) {
        ConfigResponseDB cr = configResponseDAO.create();
        cr.setProductResponse(productResponse);
        cr.setMeasurementResponse(measResponse);
        cr.setControlResponse(controlResponse);
        cr.setResponseTimeResponse(rtResponse);
        return cr;
    }

    /**
     * 
     * Get the ConfigResponse for the given ID, creating it if it does not
     * already exist.
     * 
     * 
     * 
     */
    @Transactional(readOnly=true)
    public ConfigResponseDB getConfigResponse(AppdefEntityID id) {
        ConfigResponseDB config;

        switch (id.getType()) {
            case AppdefEntityConstants.APPDEF_TYPE_PLATFORM:
                config = platformDAO.findById(id.getId()).getConfigResponse();
                break;
            case AppdefEntityConstants.APPDEF_TYPE_SERVER:
                config = serverDAO.findById(id.getId()).getConfigResponse();
                break;
            case AppdefEntityConstants.APPDEF_TYPE_SERVICE:
                config = serviceDAO.findById(id.getId()).getConfigResponse();
                break;
            case AppdefEntityConstants.APPDEF_TYPE_APPLICATION:
            default:
                throw new IllegalArgumentException("The passed entity type " + "does not support config " + "responses");
        }

        return config;
    }

    private Platform findPlatformById(Integer id) throws PlatformNotFoundException {
        Platform platform = platformDAO.get(id);

        if (platform == null) {
            throw new PlatformNotFoundException(id);
        }

        // Make sure that resource is loaded as to not get
        // LazyInitializationException
        platform.getName();

        return platform;
    }

    /**
     * 
     */
    @Transactional(readOnly=true)
    public String getPluginName(AppdefEntityID id) throws AppdefEntityNotFoundException {
        Integer intID = id.getId();
        String pname;

        switch (id.getType()) {
            case AppdefEntityConstants.APPDEF_TYPE_PLATFORM:
                Platform plat = findPlatformById(intID);
                pname = plat.getPlatformType().getPlugin();
                break;

            case AppdefEntityConstants.APPDEF_TYPE_SERVER:
                Server serv = serverDAO.get(intID);
                if (serv == null) {
                    throw new ServerNotFoundException(intID);
                }
                pname = serv.getServerType().getPlugin();
                break;

            case AppdefEntityConstants.APPDEF_TYPE_SERVICE:
                org.hyperic.hq.appdef.server.session.Service service = serviceDAO.get(intID);
                if (service == null) {
                    throw new ServiceNotFoundException(intID);
                }
                pname = service.getServiceType().getPlugin();
                break;

            case AppdefEntityConstants.APPDEF_TYPE_APPLICATION:
            default:
                throw new IllegalArgumentException("The passed entity type " + "does not support config responses");
        }

        return pname;
    }

    /**
     * Get a config response object merged through the hierarchy. All entities
     * are merged with the product's config response, and any entity lower than
     * them in the config stack. Config responses defining a specific attribute
     * will override the same attribute if it was declared lower in the
     * application stack. Only entities within the same plugin will be
     * processed, so the most likely situation is a simple service + server +
     * product or server + product merge.
     * 
     * Example: Get the SERVICE MEASUREMENT merged response: PRODUCT[platform] +
     * MEASUREMENT[platform] PRODUCT[server] + MEASUREMENT[server] +
     * PRODUCT[service] + MEASUREMENT[service]
     * 
     * Get the SERVER PRODUCT merged response: PRODUCT[platform] PRODUCT[server]
     * 
     * Get the PLATFORM PRODUCT merged response: PRODUCT[platform]
     * 
     * In addition to the configuration, some inventory properties are also
     * merged in to aid in auto-configuration done by autoinventory.
     * 
     * For Servers and Services: The install path of the server is included
     * 
     * For all Resources: The first non-loopback ip address, fqdn, platform name
     * and type.
     * 
     * @param productType One of ProductPlugin.*
     * @param id An AppdefEntityID of the object to get config for
     * 
     * @return the merged ConfigResponse
     * 
     * 
     * 
     */

    @Transactional(readOnly=true)
    public ConfigResponse getMergedConfigResponse(AuthzSubject subject, String productType, AppdefEntityID id,
                                                  boolean required) throws AppdefEntityNotFoundException,
        ConfigFetchException, EncodingException, PermissionException {
        ConfigResponseDB configValue;
        AppdefEntityID platformId = null, serverId = null, serviceId = null;
        byte[][] responseList; // List of config responses to merge
        ConfigResponse res;
        int responseIdx;
        byte[] data;
        ServerConfigStuff server = null;
        PlatformConfigStuff platform = null;
        boolean origReq = required;
        boolean isServerOrService = false;
        boolean isProductType = productType.equals(ProductPlugin.TYPE_PRODUCT);

        if (id.getType() != AppdefEntityConstants.APPDEF_TYPE_PLATFORM &&
            id.getType() != AppdefEntityConstants.APPDEF_TYPE_SERVER &&
            id.getType() != AppdefEntityConstants.APPDEF_TYPE_SERVICE) {
            throw new IllegalArgumentException(id + " doesn't support " + "config merging");
        }

        // Setup
        responseList = new byte[6][];
        responseIdx = 0;

        if (id.getType() == AppdefEntityConstants.APPDEF_TYPE_SERVICE) {
            server = getServerStuffForService(id.getId());
            if (server != null) {
                serverId = AppdefEntityID.newServerID(new Integer(server.id));
                platform = getPlatformStuffForServer(serverId.getId());
                if (platform != null) {
                    platformId = AppdefEntityID.newPlatformID(new Integer(platform.id));
                }
                serviceId = id;
                origReq = required;
                required = false;
                isServerOrService = true;
            }
        } else if (id.getType() == AppdefEntityConstants.APPDEF_TYPE_SERVER) {
            platform = getPlatformStuffForServer(id.getId());
            if (platform != null) {
                platformId = AppdefEntityID.newPlatformID(new Integer(platform.id));
                serverId = id;
                server = getServerStuffForServer(serverId.getId());
                isServerOrService = true;
            }
        } else {
            // Just the platform
            platformId = id;
            platform = getPlatformStuffForPlatform(platformId.getId());
        }

        // Platform config
        if (platformId != null) {
            // hardcode required=false for server/service types
            // e.g. unlikely that a platform will have control config
            boolean platformConfigRequired = isServerOrService ? false : required;

            configValue = getConfigResponse(platformId);
            data = getConfigForType(configValue, ProductPlugin.TYPE_PRODUCT, platformId, platformConfigRequired);
            responseList[responseIdx++] = data;

            if (!isProductType) {
                if (productType.equals(ProductPlugin.TYPE_RESPONSE_TIME)) {
                    // Skip merging of response time configuration
                    // since platforms don't have it.
                } else {
                    data = getConfigForType(configValue, productType, platformId, platformConfigRequired);
                    responseList[responseIdx++] = data;
                }
            }
        }

        // Server config (if necessary)
        if (serverId != null) {
            if (id.isServer())
                required = isProductType ? origReq : false;

            configValue = getConfigResponse(serverId);
            data = getConfigForType(configValue, ProductPlugin.TYPE_PRODUCT, serverId, required);
            responseList[responseIdx++] = data;

            if (!isProductType) {
                required = id.isServer() && origReq; // Reset the required flag

                if (productType.equals(ProductPlugin.TYPE_RESPONSE_TIME)) {
                    // Skip merging of response time configuration
                    // since servers don't have it.
                } else {
                    data = getConfigForType(configValue, productType, serverId, required);
                    responseList[responseIdx++] = data;
                }
            }
        }

        // Service config (if necessary)
        if (serviceId != null) {
            required = isProductType ? origReq : false;

            configValue = getConfigResponse(id);
            data = getConfigForType(configValue, ProductPlugin.TYPE_PRODUCT, id, required);
            responseList[responseIdx++] = data;

            if (!isProductType) {
                required = origReq; // Reset the required flag
                data = getConfigForType(configValue, productType, id, required);
                responseList[responseIdx++] = data;
            }
        }

        // Merge everything together
        res = new ConfigResponse();
        for (int i = 0; i < responseIdx; i++) {
            if (responseList[i] == null || responseList[i].length == 0) {
                continue;
            }

            res.merge(ConfigResponse.decode(responseList[i]), true);
        }

        // Set platform attributes for all resources
        try {
            if (platform != null) {
                res.setValue(ProductPlugin.PROP_PLATFORM_NAME, platform.name);
                res.setValue(ProductPlugin.PROP_PLATFORM_FQDN, platform.fqdn);
                res.setValue(ProductPlugin.PROP_PLATFORM_TYPE, platform.typeName);
                res.setValue(ProductPlugin.PROP_PLATFORM_IP, platform.ip);
                res.setValue(ProductPlugin.PROP_PLATFORM_ID, String.valueOf(platform.id));
            }
        } catch (Exception exc) {
            log.warn("Error setting platform properies: " + exc, exc);
        }

        // Set installpath attribute for server and service types.
        if (isServerOrService && server != null) {
            try {
                res.setValue(ProductPlugin.PROP_INSTALLPATH, server.installPath);
            } catch (Exception exc) {
                log.warn("Error setting installpath property: " + exc, exc);
            }
        }

        return res;
    }

    /**
     * Clear the validation error string for a config response, indicating that
     * the current config is valid
     * 
     */
    public void clearValidationError(AuthzSubject subject, AppdefEntityID id) {
        ConfigResponseDB config = getConfigResponse(id);
        config.setValidationError(null);
    }

    /**
     * Method to merge configs, maintaining any existing values that are not
     * present in the AI config (e.g. log/config track enablement)
     * 
     * @param existingBytes The existing configuration
     * @param newBytes The new configuration
     * @param overwrite TODO
     * @param force TODO
     * @return The newly merged configuration
     */
    private static byte[] mergeConfig(byte[] existingBytes, byte[] newBytes, boolean overwrite, boolean force) {
        if (force || (existingBytes == null) || (existingBytes.length == 0)) {
            return newBytes;
        }

        if ((newBytes == null) || (newBytes.length == 0)) {
            // likely a manually created platform service where
            // inventory-properties are auto-discovered but config
            // is left unchanged.
            return existingBytes;
        }

        try {
            ConfigResponse existingConfig = ConfigResponse.decode(existingBytes);
            ConfigResponse newConfig = ConfigResponse.decode(newBytes);
            existingConfig.merge(newConfig, overwrite);
            return existingConfig.encode();
        } catch (EncodingException e) {
            throw new IllegalArgumentException(e.getMessage());
        }
    }

    /**
     * Update the validation error string for a config response
     * @param validationError The error string that occured during validation.
     *        If this is null, that means that no error occurred and the config
     *        is valid.
     * 
     * 
     */
    @Transactional
    public void setValidationError(AuthzSubject subject, AppdefEntityID id, String validationError) {
        ConfigResponseDB config = getConfigResponse(id);

        if (validationError != null) {
            if (validationError.length() > MAX_VALIDATION_ERR_LEN) {
                validationError = validationError.substring(0, MAX_VALIDATION_ERR_LEN - 3) + "...";
            }
        }

        configResponseDAO.setValidationError(config, validationError);
    }

    /**
     * Set the config response for an entity/type combination.
     * 
     * @param id ID of the object to set the repsonse fo
     * @param response The response
     * @param type One of ProductPlugin.TYPE_*
     * 
     * @return an array of entities which may be affected by the change in
     *         configuration. For updates to platform and service configs, there
     *         are no other entities other than the given ID returned. If a
     *         server is updated, the associated services may require changes.
     *         The passed entity will always be returned in the array.
     * 
     * 
     * 
     */
    @Transactional
    public AppdefEntityID setConfigResponse(AuthzSubject subject, AppdefEntityID id, ConfigResponse response,
                                            String type, boolean sendConfigEvent) throws ConfigFetchException,
        AppdefEntityNotFoundException, PermissionException, EncodingException {
        byte[] productBytes = null;
        byte[] measurementBytes = null;
        byte[] controlBytes = null;
        byte[] rtBytes = null;

        if (type.equals(ProductPlugin.TYPE_PRODUCT)) {
            productBytes = response.encode();
        } else if (type.equals(ProductPlugin.TYPE_MEASUREMENT)) {
            measurementBytes = response.encode();
        } else if (type.equals(ProductPlugin.TYPE_CONTROL)) {
            controlBytes = response.encode();
        } else if (type.equals(ProductPlugin.TYPE_RESPONSE_TIME)) {
            rtBytes = response.encode();
        } else if (type.equals(ProductPlugin.TYPE_AUTOINVENTORY)) {
        } else {
            throw new IllegalArgumentException("Unknown config type: " + type);
        }

        ConfigResponseDB existingConfig = getConfigResponse(id);
        return configureResponse(subject, existingConfig, id, productBytes, measurementBytes, controlBytes, rtBytes,
            null, sendConfigEvent, false);
    }

    /**
     * 
     *
     */
    @Transactional
    public AppdefEntityID configureResponse(AuthzSubject subject, ConfigResponseDB existingConfig,
                                            AppdefEntityID appdefID, byte[] productConfig, byte[] measurementConfig,
                                            byte[] controlConfig, byte[] rtConfig, Boolean userManaged,
                                            boolean sendConfigEvent, boolean force) {
        boolean wasUpdated = false;
        byte[] configBytes;

        boolean overwrite = ((userManaged != null) && userManaged.booleanValue()) || // via
                            // UI
                            // or
                            // CLI
                            !existingConfig.isUserManaged(); // via AI, dont
        // overwrite
        // changes made via
        // UI or CLI

        configBytes = mergeConfig(existingConfig.getProductResponse(), productConfig, overwrite, force);
        if (!AICompare.configsEqual(configBytes, existingConfig.getProductResponse())) {
            existingConfig.setProductResponse(configBytes);
            wasUpdated = true;
        }

        configBytes = mergeConfig(existingConfig.getMeasurementResponse(), measurementConfig, overwrite, force);
        if (!AICompare.configsEqual(configBytes, existingConfig.getMeasurementResponse())) {
            existingConfig.setMeasurementResponse(configBytes);
            wasUpdated = true;
        }

        configBytes = mergeConfig(existingConfig.getControlResponse(), controlConfig, overwrite, false);
        if (!AICompare.configsEqual(configBytes, existingConfig.getControlResponse())) {
            existingConfig.setControlResponse(configBytes);
            wasUpdated = true;
        }

        configBytes = mergeConfig(existingConfig.getResponseTimeResponse(), rtConfig, overwrite, false);
        if (!AICompare.configsEqual(configBytes, existingConfig.getResponseTimeResponse())) {
            existingConfig.setResponseTimeResponse(configBytes);
            wasUpdated = true;
        }

        if (userManaged != null && existingConfig.getUserManaged() != userManaged.booleanValue()) {
            existingConfig.setUserManaged(userManaged.booleanValue());
            wasUpdated = true;
        }

        if (wasUpdated) {
            // XXX: Need to cascade and send events for each resource that may
            // have been affected by this config update.
            if (sendConfigEvent) {
                ResourceUpdatedZevent event = new ResourceUpdatedZevent(subject, appdefID);
                zeventManager.enqueueEventAfterCommit(event);
            }

            return appdefID;
        } else {
            return null;
        }
    }

    private byte[] getConfigForType(ConfigResponseDB val, String productType, AppdefEntityID id, boolean fail)
        throws ConfigFetchException {
        byte[] res;

        if (productType.equals(ProductPlugin.TYPE_PRODUCT)) {
            res = val.getProductResponse();
        } else if (productType.equals(ProductPlugin.TYPE_CONTROL)) {
            res = val.getControlResponse();
        } else if (productType.equals(ProductPlugin.TYPE_MEASUREMENT)) {
            res = val.getMeasurementResponse();
        } else if (productType.equals(ProductPlugin.TYPE_AUTOINVENTORY)) {
            res = val.getAutoInventoryResponse();
        } else if (productType.equals(ProductPlugin.TYPE_RESPONSE_TIME)) {
            res = val.getResponseTimeResponse();
        } else {
            throw new IllegalArgumentException("Unknown product type");
        }

        if ((res == null || res.length == 0) && fail) {
            throw new ConfigFetchException(productType, id);
        }
        return res;
    }

    private ServerConfigStuff getServerStuffForService(Integer id) throws AppdefEntityNotFoundException {

        org.hyperic.hq.appdef.server.session.Service service = serviceDAO.findById(id);
        Server server = service.getServer();
        if (server == null) {
            return null;
        }
        return new ServerConfigStuff(server.getId().intValue(), server.getInstallPath());
    }

    private ServerConfigStuff getServerStuffForServer(Integer id) throws AppdefEntityNotFoundException {

        Server server = serverDAO.findById(id);

        return new ServerConfigStuff(server.getId().intValue(), server.getInstallPath());
    }

    private PlatformConfigStuff getPlatformStuffForServer(Integer id) throws AppdefEntityNotFoundException {

        Server server = serverDAO.findById(id);
        Platform platform = server.getPlatform();
        if (platform == null) {
            return null;
        }

        PlatformConfigStuff pConfig = new PlatformConfigStuff(platform.getId().intValue(), platform.getName(), platform
            .getFqdn(), platform.getPlatformType().getName());
        loadPlatformIp(platform, pConfig);
        return pConfig;
    }

    private PlatformConfigStuff getPlatformStuffForPlatform(Integer id) throws AppdefEntityNotFoundException {

        Platform platform = platformDAO.findById(id);

        PlatformConfigStuff pConfig = new PlatformConfigStuff(platform.getId().intValue(), platform.getName(), platform
            .getFqdn(), platform.getPlatformType().getName());
        loadPlatformIp(platform, pConfig);
        return pConfig;
    }

    private void loadPlatformIp(Platform platform, PlatformConfigStuff pConfig) throws AppdefEntityNotFoundException {

        Collection<Ip> ips = platform.getIps();
        for (Ip ip : ips) {

            if (!ip.getAddress().equals("127.0.0.1")) {
                // First non-loopback address
                pConfig.ip = ip.getAddress();
                break;
            }
        }
    }

    // Utility classes used by getMergedConfig
    class ServerConfigStuff {
        public int id;
        public String installPath;

        public ServerConfigStuff(int id, String installPath) {
            this.id = id;
            this.installPath = installPath;
        }
    }

    class PlatformConfigStuff {
        public int id;
        public String ip;
        public String name;
        public String fqdn;
        public String typeName;

        public PlatformConfigStuff(int id, String name, String fqdn, String typeName) {
            this.id = id;
            this.name = name;
            this.fqdn = fqdn;
            this.typeName = typeName;
        }
    }
}