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

    private ResolveListener mListener;

    public DependencyResolver(PomRepository repository) {
        this.repository = repository;
        this.resolvedPoms = new HashMap<>();
    }

    public void setResolveListener(ResolveListener listener) {
        mListener = listener;
    }

    public interface ResolveListener {
        void onResolve(String message);
        void onFailure(String message);
    }

    public List<Pom> resolveDependencies(List<Dependency> declaredDependencies) {
        List<Pom> poms = new ArrayList<>();
        for (Dependency dependency : declaredDependencies) {
            if (mListener != null) {
                mListener.onResolve("Getting POM: " + dependency);
            }
            Pom pom = repository.getPom(dependency.toString());
            if (pom != null) {
                poms.add(pom);
            } else {
                if (mListener != null) {
                    mListener.onFailure("Unable to retrieve POM of " + dependency);
                }
            }
        }
        return resolve(poms);
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

        if (mListener != null) {
            mListener.onResolve("Resolving " + pom);
        }

        for (Dependency dependency : pom.getDependencies()) {
            if ("test".equals(dependency.getScope())) {
                continue;
            }

            Pom resolvedPom = repository.getPom(dependency.toString());
            if (resolvedPom == null) {
                if (mListener != null) {
                    mListener.onFailure("Failed to resolve " + dependency);
                }
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
