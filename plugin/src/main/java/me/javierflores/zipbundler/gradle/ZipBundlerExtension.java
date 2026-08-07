package me.javierflores.zipbundler.gradle;

import org.gradle.api.Action;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.ProviderFactory;

import javax.inject.Inject;
import java.util.List;

public abstract class ZipBundlerExtension {
    private final NamedDomainObjectContainer<ZipBundle> bundles;
    private final NamedDomainObjectContainer<ZipBundleContainer> containers;
    private final ProviderFactory providers;

    @Inject
    public ZipBundlerExtension(ObjectFactory objects, ProviderFactory providers) {
        bundles = objects.domainObjectContainer(ZipBundle.class);
        containers = objects.domainObjectContainer(ZipBundleContainer.class);
        this.providers = providers;
    }

    public final NamedDomainObjectContainer<ZipBundle> getBundles() {
        return bundles;
    }

    /**
     * Lazily returns the explicitly declared bundles assigned to the given container.
     * The result reflects container properties assigned later in bundle configuration blocks.
     */
    public final Provider<List<ZipBundle>> getBundles(ZipBundleContainer container) {
        return getBundles(container.getName());
    }

    /**
     * Lazily returns the explicitly declared bundles assigned to the named container.
     * The result reflects container properties assigned later in bundle configuration blocks.
     */
    public final Provider<List<ZipBundle>> getBundles(String containerName) {
        return providers.provider(() -> bundles.stream()
            .filter(bundle -> containerName.equals(bundle.getContainer().get()))
            .toList()
        );
    }

    public final void bundles(Action<? super NamedDomainObjectContainer<ZipBundle>> action) {
        action.execute(bundles);
    }

    public final NamedDomainObjectContainer<ZipBundleContainer> getContainers() {
        return containers;
    }

    public final void containers(Action<? super NamedDomainObjectContainer<ZipBundleContainer>> action) {
        action.execute(containers);
    }
}
