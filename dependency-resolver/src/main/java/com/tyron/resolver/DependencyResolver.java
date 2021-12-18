package com.tyron.resolver;

import com.tyron.resolver.model.Dependency;
import com.tyron.resolver.model.Pom;
import com.tyron.resolver.repository.PomRepository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class DependencyResolver {

    private final PomRepository repository;
    private final Map<Pom, String> resolvedPoms;

    public DependencyResolver(PomRepository repository) {
        this.repository = repository;
        this.resolvedPoms = new HashMap<>();
    }

    /**
     * Resolve the list of given dependencies, prioritizing the latest versions of
     * the conflicting libraries
     */
    public List<Pom> resolve(List<Pom> declaredDependencies) {
        for (Pom pom : declaredDependencies) {
            resolve(pom);
        }
        return new ArrayList<>(resolvedPoms.keySet());
    }

    private void resolve(Pom pom) {
        if (resolvedPoms.containsKey(pom)) {
            String resolvedVersion = resolvedPoms.get(pom);
            String thisVersion = pom.getVersionName();
            int result = getHigherVersion(resolvedVersion, thisVersion);
            if (result == 0) {
                return;
            }
            if (result > 0) {
                return;
            } else {
                resolvedPoms.remove(pom);
            }
        }

        for (Dependency dependency : pom.getDependencies()) {
            if ("test".equals(dependency.getScope())) {
                continue;
            }

            Pom resolvedPom = repository.getPom(dependency.toString());
            if (resolvedPom == null) {
                continue;
            }
            resolve(resolvedPom);
        }
        resolvedPoms.put(pom, pom.getVersionName());
    }

    private int getHigherVersion(String firstVersion, String secondVersion) {
        ComparableVersion firstComparableVersion = new ComparableVersion(firstVersion);
        ComparableVersion secondComparableVersion = new ComparableVersion(secondVersion);
        return firstComparableVersion.compareTo(secondComparableVersion);
    }
}
