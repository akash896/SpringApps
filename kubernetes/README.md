# Kubernetes deployment topology

The manifests create the `resilience-demo` namespace in each Kubernetes cluster.
Every pod contains one application container, and each deployment has a Service
for in-cluster REST traffic.

| Application | Cluster 1 | Cluster 2 | Cluster 3 | Total |
| --- | ---: | ---: | ---: | ---: |
| pricing-service (app1) | 2 | 1 | 1 | 4 |
| inventory-service (app2) | 2 | 1 | 1 | 4 |
| order-service (app3) | 2 | 1 | 1 | 4 |

## Prerequisites

Build and publish `pricing-service:1.0.0`, `inventory-service:1.0.0`, and
`order-service:1.0.0` to an image registry reachable by all three clusters.
Before applying, replace the image names in `base/deployments.yml` with the
fully qualified names in that registry, or load the images into every cluster.

## Deploy and verify

The three context arguments must identify the intended clusters in the listed
order; this avoids accidentally applying the manifests to an unrelated context.

```sh
sh kubernetes/scripts/deploy.sh <cluster1-context> <cluster2-context> <cluster3-context>
sh kubernetes/scripts/verify-replicas.sh <cluster1-context> <cluster2-context> <cluster3-context>
```

`verify-replicas.sh` succeeds only when every deployment has the configured
number of available replicas: 2 in Cluster 1 and 1 in each of Clusters 2 and 3.
