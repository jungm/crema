package io.github.jungm.crema.internal.cdi;

import java.lang.reflect.Type;

import io.github.jungm.crema.internal.model.InstanceSource;
import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;

/**
 * Obtains a contextual reference of a bean per call, so scopes, interceptors and injection apply. Dependent
 * instances are destroyed when the call ends.
 */
record CdiInstanceSource(BeanManager beanManager, Bean<?> bean) implements InstanceSource {

    @Override
    public Handle acquire() {
        CreationalContext<?> context = beanManager.createCreationalContext(bean);
        Object reference = beanManager.getReference(bean, type(), context);
        return new Handle() {
            @Override
            public Object get() {
                return reference;
            }

            @Override
            public void close() {
                context.release();
            }
        };
    }

    private Type type() {
        return bean.getTypes().contains(bean.getBeanClass()) ? bean.getBeanClass() : Object.class;
    }
}
