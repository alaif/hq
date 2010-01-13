package org.hyperic.hq.ui.security;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.hyperic.hq.auth.shared.SessionManager;
import org.hyperic.hq.ui.util.RequestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
/**
 * Extension of {@link SecurityContextLogoutHandler} that updates the internal SessionManager
 * TODO remove this in favor of just using the SecurityContext (upon removal of SessionManager)
 * @author jhickey
 *
 */
public class SessionManagingLogoutHandler extends SecurityContextLogoutHandler {
    
    private SessionManager sessionManager;
    
    private final Log log = LogFactory.getLog(SessionManagingLogoutHandler.class);
    
  
    @Autowired
    public SessionManagingLogoutHandler(SessionManager sessionManager) {
        super();
        this.sessionManager = sessionManager;
    }



    @Override
    public void logout(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        try {
            sessionManager.invalidate(RequestUtils.getSessionIdInt(request));
        } catch (Exception e) {
            log.warn("Error invalidating the user associated with this session: " + e.getMessage());
        }
        super.logout(request, response, authentication);
      
    }

}
