package io.github.jungm.crema.internal.cdi;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.mcpjava.server.ContentEncoder;
import org.mcpjava.server.Icon;
import org.mcpjava.server.IconProvider;

import io.github.jungm.crema.internal.invoke.ContentEncoders;
import io.github.jungm.crema.internal.invoke.Mapping;
import io.github.jungm.crema.internal.model.FeatureScanner;
import io.github.jungm.crema.internal.model.IconLookup;
import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.spi.AfterDeploymentValidation;
import jakarta.enterprise.inject.spi.AnnotatedMethod;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.DeploymentException;
import jakarta.enterprise.inject.spi.Extension;
import jakarta.enterprise.inject.spi.ProcessManagedBean;

/**
 * Discovers Feature Methods, Completion Methods and {@link ContentEncoder}s on managed beans, and validates them
 * after deployment validation. Each problem becomes a deployment problem.
 */
public class CremaExtension implements Extension {

    private record Found(Bean<?> bean, Class<?> beanClass, Method method) {
    }

    private final List<Found> found = new ArrayList<>();
    private final List<Bean<?>> encoders = new ArrayList<>();

    <T> void discover(@Observes ProcessManagedBean<T> event) {
        Class<?> beanClass = event.getAnnotatedBeanClass().getJavaClass();
        for (AnnotatedMethod<? super T> method : event.getAnnotatedBeanClass().getMethods()) {
            if (FeatureScanner.isAnnotated(method.getJavaMember())) {
                found.add(new Found(event.getBean(), beanClass, method.getJavaMember()));
            }
        }
        if (ContentEncoder.class.isAssignableFrom(beanClass)) {
            encoders.add(event.getBean());
        }
    }

    void validate(@Observes AfterDeploymentValidation event, BeanManager beanManager) {
        Mapping mapping = Mapping.create();
        IconLookup icons = icons(beanManager);
        FeatureScanner scanner = new FeatureScanner(mapping, icons);
        for (Found f : found) {
            scanner.scan(f.beanClass(), f.method(), new CdiInstanceSource(beanManager, f.bean()));
        }
        scanner.problems().forEach(problem -> event.addDeploymentProblem(new DeploymentException(problem)));
        List<ContentEncoders.Candidate> candidates = encoders.stream()
                .map(bean -> new ContentEncoders.Candidate(encodedType(bean),
                        new CdiInstanceSource(beanManager, bean)))
                .toList();
        CremaDeployment.featuresDiscovered(new CremaDeployment.Catalog(scanner.features(), scanner.completions(),
                mapping, new ContentEncoders(() -> candidates), icons),
                problem -> event.addDeploymentProblem(new DeploymentException(problem)));
        found.clear();
        encoders.clear();
    }

    /**
     * Obtains {@link IconProvider}s as CDI beans, or through their no-argument constructor when they aren't beans.
     */
    private static IconLookup icons(BeanManager beanManager) {
        IconLookup reflective = IconLookup.reflective();
        return (provider, type, name) -> {
            Set<Bean<?>> beans = beanManager.getBeans(provider);
            Bean<?> bean = beans.isEmpty() ? null : beanManager.resolve(beans);
            if (bean == null) {
                return reflective.icons(provider, type, name);
            }
            CreationalContext<?> context = beanManager.createCreationalContext(bean);
            try {
                List<Icon> icons = ((IconProvider) beanManager.getReference(bean, provider, context))
                        .getIcons(type, name);
                return icons == null ? List.of() : icons;
            } finally {
                context.release();
            }
        };
    }

    /**
     * The {@code T} of a bean's {@code ContentEncoder<T>} type, or {@code null} if it isn't a class.
     */
    private static Class<?> encodedType(Bean<?> bean) {
        for (Type type : bean.getTypes()) {
            if (type instanceof ParameterizedType parameterized && parameterized.getRawType() == ContentEncoder.class
                    && parameterized.getActualTypeArguments()[0] instanceof Class<?> encoded) {
                return encoded;
            }
        }
        return null;
    }
}
