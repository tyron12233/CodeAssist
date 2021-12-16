package com.tyron.resolver;

import com.tyron.resolver.model.Dependency;
import com.tyron.resolver.model.Pom;
import com.tyron.resolver.repository.PomRepository;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class DependencyResolver {

    private final PomRepository repository;

    public DependencyResolver(PomRepository repository) {
        this.repository = repository;
    }

    /**
     * Resolve the list of given dependencies, prioritizing the latest versions of
     * the conflicting libraries
     */
    public Collection<Pom> resolve(List<Pom> declaredDependencies) {
        Map<Integer, Pom> resolved = new HashMap<>();
        for (Pom pom : declaredDependencies) {
            for (Pom resolvedPom : resolve(pom).values()) {
                if (resolved.containsKey(resolvedPom.hashCode())) {
                    Pom first = resolved.get(resolvedPom.hashCode());
                    Pom second = resolvedPom;
                    resolved.remove(resolvedPom.hashCode());
                    resolved.put(resolvedPom.hashCode(), getHigherVersion(first, second));
                } else {
                    resolved.put(resolvedPom.hashCode(), resolvedPom);
                }
            }
        }
        return resolved.values();
    }

    private Map<Integer, Pom> resolve(Pom pom) {
        Map<Integer, Pom> resolved = new HashMap<>();
        resolved.put(pom.hashCode(), pom);
        for (Dependency dependency : pom.getDependencies()) {
            if ("test".equals(dependency.getScope())) {
                continue;
            }
            Pom dependencyPom = repository.getPom(dependency.toString());
            if (dependencyPom == null) {
                continue;
            }
            for (Pom resolvedPom : resolve(dependencyPom).values()) {
                if (resolved.containsKey(resolvedPom.hashCode())) {
                    Pom first = resolved.get(resolvedPom.hashCode());
                    Pom second = resolvedPom;
                    resolved.remove(resolvedPom.hashCode());
                    resolved.put(resolvedPom.hashCode(), getHigherVersion(first, second));
                } else {
                    resolved.put(resolvedPom.hashCode(), resolvedPom);
                }
            }
        }
        return resolved;
    }

    private Pom getHigherVersion(Pom first, Pom second) {
        String firstVersion = first.getVersionName();
        String secondVersion = second.getVersionName();

        if (secondVersion == null) {
            return first;
        }
        if (firstVersion == null) {
            return second;
        }
        ComparableVersion firstComparableVersion = new ComparableVersion(firstVersion);
        ComparableVersion secondComparableVersion = new ComparableVersion(secondVersion);

        int result = firstComparableVersion.compareTo(secondComparableVersion);
        if (result == 0) {
            return first;
        }
        if (result > 0) {
            return first;
        }
        return second;
    }
}
