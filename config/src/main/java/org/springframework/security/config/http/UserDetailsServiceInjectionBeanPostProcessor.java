package org.springframework.security.config.http;

import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.beans.BeansException;
import org.springframework.beans.PropertyValue;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.HierarchicalBeanFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.ApplicationContextException;
import org.springframework.security.config.authentication.AbstractUserDetailsServiceBeanDefinitionParser;
import org.springframework.security.config.authentication.CachingUserDetailsService;
import org.springframework.security.core.userdetails.UserDetailsByNameServiceWrapper;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationProvider;
import org.springframework.security.web.authentication.rememberme.AbstractRememberMeServices;
import org.springframework.util.Assert;

/**
 * Registered by {@link HttpSecurityBeanDefinitionParser} to inject a UserDetailsService into
 * the X509Provider, RememberMeServices and OpenIDAuthenticationProvider instances created by
 * the namespace.
 *
 * @author Luke Taylor
 * @since 2.0.2
 */
public class UserDetailsServiceInjectionBeanPostProcessor implements BeanPostProcessor, BeanFactoryAware {
    private final Log logger = LogFactory.getLog(getClass());

    private ConfigurableListableBeanFactory beanFactory;
    private final String x509ProviderId;
    private final String rememberMeServicesId;
    private final String openIDProviderId;

    public UserDetailsServiceInjectionBeanPostProcessor(String x509ProviderId, String rememberMeServicesId,
            String openIDProviderId) {
        this.x509ProviderId = x509ProviderId;
        this.rememberMeServicesId = rememberMeServicesId;
        this.openIDProviderId = openIDProviderId;
    }

    public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
        if(beanName == null) {
            return bean;
        }
        if (beanName.equals(x509ProviderId)) {
            injectUserDetailsServiceIntoX509Provider((PreAuthenticatedAuthenticationProvider) bean);
        } else if (beanName.equals(rememberMeServicesId)) {
            injectUserDetailsServiceIntoRememberMeServices(bean);
        } else if (beanName.equals(openIDProviderId)) {
            injectUserDetailsServiceIntoOpenIDProvider(bean);
        }

        return bean;
    }

    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        return bean;
    }

    private void injectUserDetailsServiceIntoRememberMeServices(Object rms) {
        if (!(rms instanceof AbstractRememberMeServices)) {
            logger.info("RememberMeServices is not an instance of AbstractRememberMeServices. UserDetailsService will" +
                    " not be automatically injected.");
            return;
        }

        AbstractRememberMeServices services = (AbstractRememberMeServices) rms;

        BeanDefinition beanDefinition = beanFactory.getBeanDefinition(rememberMeServicesId);
        PropertyValue pv = beanDefinition.getPropertyValues().getPropertyValue("userDetailsService");

        if (pv == null) {
            // If it doesn't already have a UserDetailsService set, then set it.
            services.setUserDetailsService(getUserDetailsService());
        } else {
            // If already set, then attempt to locate a caching version of the injected UserDetailsService
            UserDetailsService cachingUserService = getCachingUserService(pv.getValue());

            if (cachingUserService != null) {
                services.setUserDetailsService(cachingUserService);
            }
        }
    }

    private void injectUserDetailsServiceIntoX509Provider(PreAuthenticatedAuthenticationProvider provider) {
        BeanDefinition beanDefinition = beanFactory.getBeanDefinition(x509ProviderId);
        PropertyValue pv = beanDefinition.getPropertyValues().getPropertyValue("preAuthenticatedUserDetailsService");
        UserDetailsByNameServiceWrapper wrapper = new UserDetailsByNameServiceWrapper();

        if (pv == null) {
            wrapper.setUserDetailsService(getUserDetailsService());
            provider.setPreAuthenticatedUserDetailsService(wrapper);
        } else {
            RootBeanDefinition preAuthUserService = (RootBeanDefinition) pv.getValue();
            Object userService =
                preAuthUserService.getPropertyValues().getPropertyValue("userDetailsService").getValue();

            UserDetailsService cachingUserService = getCachingUserService(userService);

            if (cachingUserService != null) {
                wrapper.setUserDetailsService(cachingUserService);
                provider.setPreAuthenticatedUserDetailsService(wrapper);
            }
        }
    }

    private void injectUserDetailsServiceIntoOpenIDProvider(Object bean) {
        BeanDefinition beanDefinition = beanFactory.getBeanDefinition(openIDProviderId);
        PropertyValue pv = beanDefinition.getPropertyValues().getPropertyValue("userDetailsService");

        if (pv == null) {
            BeanWrapperImpl beanWrapper = new BeanWrapperImpl(bean);
            beanWrapper.setPropertyValue("userDetailsService", getUserDetailsService());
        }
    }

    /**
     * Obtains a user details service for use in RememberMeServices etc. Will return a caching version
     * if available so should not be used for beans which need to separate the two.
     */
    UserDetailsService getUserDetailsService() {
        Map<String,?> beans = getBeansOfType(CachingUserDetailsService.class);

        if (beans.size() == 0) {
            beans = getBeansOfType(UserDetailsService.class);
        }

        if (beans.size() == 0) {
            throw new ApplicationContextException("No UserDetailsService registered.");

        } else if (beans.size() > 1) {
            throw new ApplicationContextException("More than one UserDetailsService registered. Please " +
                    "use a specific Id reference in <remember-me/> <openid-login/> or <x509 /> elements.");
        }

        return (UserDetailsService) beans.values().toArray()[0];
    }

    private UserDetailsService getCachingUserService(Object userServiceRef) {
        Assert.isInstanceOf(RuntimeBeanReference.class, userServiceRef,
                "userDetailsService property value must be a RuntimeBeanReference");

        String id = ((RuntimeBeanReference)userServiceRef).getBeanName();
        // Overwrite with the caching version if available
        String cachingId = id + AbstractUserDetailsServiceBeanDefinitionParser.CACHING_SUFFIX;

        if (beanFactory.containsBeanDefinition(cachingId)) {
            return (UserDetailsService) beanFactory.getBean(cachingId);
        }

        return null;
    }

    private Map<String,?> getBeansOfType(Class<?> type) {
        Map<String,?> beans = beanFactory.getBeansOfType(type);

        // Check ancestor bean factories if they exist and the current one has none of the required type
        BeanFactory parent = beanFactory.getParentBeanFactory();
        while (parent != null && beans.size() == 0) {
            if (parent instanceof ListableBeanFactory) {
                beans = ((ListableBeanFactory)parent).getBeansOfType(type);
            }
            if (parent instanceof HierarchicalBeanFactory) {
                parent = ((HierarchicalBeanFactory)parent).getParentBeanFactory();
            } else {
                break;
            }
        }

        return beans;
    }


    public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
        this.beanFactory = (ConfigurableListableBeanFactory) beanFactory;
    }
}
