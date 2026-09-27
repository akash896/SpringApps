# Kubernetes deployment topology

The manifests create the `resilience-demo` namespace in each Kubernetes cluster.
Every pod contains one application container, and each deployment has a Service
for in-cluster REST traffic. The `hystrix-dashboard` deployment runs one pod in
each cluster and reads the local services' `/hystrix.stream` endpoints.

| Application | Cluster 1 | Cluster 2 | Cluster 3 | Total |
| --- | ---: | ---: | ---: | ---: |
| pricing-service (app1) | 2 | 1 | 1 | 4 |
| inventory-service (app2) | 2 | 1 | 1 | 4 |
| order-service (app3) | 2 | 1 | 1 | 4 |
| hystrix-dashboard | 1 | 1 | 1 | 3 |

## Prerequisites

Build and publish `pricing-service:1.0.0`, `inventory-service:1.0.0`,
`order-service:1.0.0`, and `hystrix-dashboard:1.0.0` to an image registry
reachable by all three clusters. Before applying, replace the image names in
`base/deployments.yml` with the fully qualified names in that registry, or load
the images into every cluster.

## Deploy and verify

The three context arguments must identify the intended clusters in the listed
order; this avoids accidentally applying the manifests to an unrelated context.

```sh
sh kubernetes/scripts/deploy.sh <cluster1-context> <cluster2-context> <cluster3-context>
sh kubernetes/scripts/verify-replicas.sh <cluster1-context> <cluster2-context> <cluster3-context>
```

`verify-replicas.sh` succeeds only when every application deployment has the
configured number of available replicas: 2 in Cluster 1 and 1 in each of
Clusters 2 and 3. It also verifies one ready `hystrix-dashboard` pod in each
cluster.

## Local Kind test

For a local-only setup with no OCI dependency, run:

```sh
sh kubernetes/scripts/local-kind-test.sh
```

The script creates three Kind clusters, builds the four images locally, loads
them into each cluster, deploys the manifests, and verifies replica counts.

## Check pods and services

Use the same namespace in every cluster:

```sh
kubectl --context kind-cluster1 -n resilience-demo get deployments,pods,services
kubectl --context kind-cluster2 -n resilience-demo get deployments,pods,services
kubectl --context kind-cluster3 -n resilience-demo get deployments,pods,services
```

Check rollout status:

```sh
kubectl --context kind-cluster1 -n resilience-demo rollout status deployment/pricing-service
kubectl --context kind-cluster1 -n resilience-demo rollout status deployment/inventory-service
kubectl --context kind-cluster1 -n resilience-demo rollout status deployment/order-service
kubectl --context kind-cluster1 -n resilience-demo rollout status deployment/hystrix-dashboard
```

Repeat with `kind-cluster2` and `kind-cluster3`, or use:

```sh
sh kubernetes/scripts/verify-replicas.sh kind-cluster1 kind-cluster2 kind-cluster3
```

## Open the dashboard from a cluster

Port-forward one cluster's dashboard to localhost:

```sh
kubectl --context kind-cluster1 -n resilience-demo port-forward service/hystrix-dashboard 8090:8090
```

Then open:

```text
http://localhost:8090
```

The dashboard in that cluster reads the in-cluster service streams:

```text
http://localhost:8090/streams/pricing
http://localhost:8090/streams/inventory
http://localhost:8090/streams/order
```

Sample a proxied stream:

```sh
curl -N http://localhost:8090/streams/order
```

## Send load to a cluster

Port-forward the three application services:

```sh
kubectl --context kind-cluster1 -n resilience-demo port-forward service/order-service 8081:8081
kubectl --context kind-cluster1 -n resilience-demo port-forward service/inventory-service 8082:8082
kubectl --context kind-cluster1 -n resilience-demo port-forward service/pricing-service 8083:8083
```

Run normal load:

```sh
python3 loadTest.py
```

Run heavy load:

```sh
python3 heavyLoadTest.py --workers 150
```

Keep the dashboard port-forward running in another terminal to watch metrics
populate while the load scripts run.

## Tear down local Kind clusters

When you are finished with local clusters:

```sh
kind delete cluster --name cluster1
kind delete cluster --name cluster2
kind delete cluster --name cluster3
```
