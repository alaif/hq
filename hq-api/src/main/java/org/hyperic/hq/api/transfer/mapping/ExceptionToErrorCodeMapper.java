/* **********************************************************************
/*
 * NOTE: This copyright does *not* cover user programs that use Hyperic
 * program services by normal system calls through the application
 * program interfaces provided as part of the Hyperic Plug-in Development
 * Kit or the Hyperic Client Development Kit - this is merely considered
 * normal use of the program, and does *not* fall under the heading of
 * "derived work".
 *
 * Copyright (C) [2004-2010], VMware, Inc.
 * This file is part of Hyperic.
 *
 * Hyperic is free software; you can redistribute it and/or modify
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
package org.hyperic.hq.api.transfer.mapping;

import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

import org.apache.commons.logging.Log;
import org.hyperic.hq.api.model.resources.FailedResource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;


public class ExceptionToErrorCodeMapper  {
    private static final String DEFAULT_EXCEPTION = "default";
    
    private static final ResourceBundle errorCodesBundle = ResourceBundle.getBundle("org.hyperic.hq.api.transfer.mapping.APIErrorCodes", Locale.ENGLISH) ; 
    
    private Map<String,String> exceptionToErrorCodeMap;

    @Autowired
    @Qualifier("restApiLogger")
    private Log logger;    		
    public Log getLogger() {
        return logger;
    }    
    public void setLogger(Log logger) {
        this.logger = logger;
    }     
    
    public Map<String,String> getExceptionToErrorCodeMap() {
        return exceptionToErrorCodeMap;
    }
    
    public void setExceptionToErrorCodeMap(Map<String,String> exceptionToErrorCodeMap) {
        this.exceptionToErrorCodeMap = exceptionToErrorCodeMap;
    }    

    /* (non-Javadoc)
	 * @see org.hyperic.hq.api.transfer.mapping.IExcpetionToErrorCodeMapper#getErrorCode(java.lang.Exception)
	 */
    public String getErrorCode(Throwable exc) {
        if (null == exc) {
            logger.warn("No error code for null exception");
            return null;
        }
        String errorCode = exceptionToErrorCodeMap.get(exc.getClass().getName());
        if (null == errorCode) {
            errorCode = exceptionToErrorCodeMap.get(DEFAULT_EXCEPTION);
        }
        return errorCode;
    }
    
    public final String getDescription(final String errorCode, final Object...args) { 
    	String description = null ;  
    	try{ 
    		description = errorCodesBundle.getString(errorCode) ;
    		return (description == null || description.isEmpty() ? null : String.format(description, args) ) ; 
    	}catch(MissingResourceException mre) {}//EO catch block
    	
    	return null ; 
    }//EOM 
    
    public final FailedResource newFailedResource(final Throwable t, final String resourceID, final String additionalDescription, final Object...args) {
    	return this.newFailedResource(resourceID, this.getErrorCode(t), additionalDescription, args)  ; 
    }//EOM
    
    public final FailedResource newFailedResource(final String resourceID, final String errorCode, String additionalDescription, final Object...args) {
    	if(additionalDescription == null) additionalDescription =  this.getDescription(errorCode, args) ; 
    	return new FailedResource(resourceID, errorCode, additionalDescription) ;
    }//EOM 
    
    public final void log(final Throwable t) {
    	this.log(t, null/*additionalMessage*/) ; 
    }//EOM 
    
    public final void log(final Throwable t, final String additionalMessage) { 
    	this.logger.error(additionalMessage, t) ; 
    }//EOM 
    
    
}