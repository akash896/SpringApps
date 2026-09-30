# Minimal Resilience4j dashboard integration

The applications export JMX metrics. One external collector per Kubernetes cluster
reads them and streams measurements to the dashboard hub. No dashboard Maven
dependency, HTTP exporter, SSE endpoint, or collector runs inside these applications.

## Refresh interval

JMX MBeans are read on demand; the applications do not push metrics on a timer.
Each service supplies its requested collection and browser publication intervals in
application configuration. For `application.properties` use:

```properties
resilience4j.dashboard.pollIntervalMillis=500
resilience4j.dashboard.browserPublishIntervalMillis=750
```

The equivalent keys are present in these services' `application.yml` files. The small
registration block in `ResilienceMetricsConfiguration.java` exposes only these two resolved
values through the standard dashboard timing MBean. This bridge is necessary because an
external collector cannot read an application's Spring configuration file directly.

This requests a 500 ms delay after each completed pod poll and publishes the latest
aggregate over SSE every 750 ms. If live applications request different browser intervals,
the fastest request controls the single shared browser stream.
`flushIntervalMillis` controls batching from collector to hub and is separate; the
recommended value is 100 ms. Positive millisecond values are accepted without an upper
limit. One millisecond is the scheduler's technical floor, not a safe production default.
These intervals bound scheduling frequency but do not
guarantee 500 ms end-to-end latency across JMX, cluster and network processing.

## Files retained

- Each service's `ResilienceMetricsConfiguration.java`: cumulative duration and
  execution counts, rejection/timeout counts, and pool measurements missing from
  the native meters. These support the dashboard's mean latency and pool guidance.
- `profiles.json`: the exact MBean/attribute mappings for the three applications.
  Put its top-level profile entries inside the collector configuration's `profiles` object.
- Spring Boot's auto-configured JMX registry and the Boot 3 property
  `management.jmx.metrics.export.enabled`, with the common `application` tag.

The existing Maven dependencies already provide Resilience4j, Micrometer, and its
JMX registry. Application policies, fallback placement, HTTP clients, original
load scripts, tests, and Kubernetes manifests are unchanged by this minimal setup.
Local cluster orchestration, additional test tools, and generated credentials
have been removed. Existing application manifests alone do not enable remote JMX.

## Connect a multi-cluster deployment

1. Build each application normally with Java 17. Each replica must run its own JVM
   in its own pod. The supplemental meters bind once to the YAML-defined registry
   entries; dynamically added or replaced instances need lifecycle-aware binding.
2. Configure authenticated TLS remote JMX on every application's JVM. Fix both
   the registry and RMI connector ports (for example, 9010), use a read-only
   monitoring account, and mount credentials and keystores through Secrets.
   Advertise the pod's current address using `java.rmi.server.hostname`; inject
   `status.podIP` through the Downward API. Protect password files with owner-only
   permissions. Certificates must match the advertised addresses and be rotated
   through your platform's certificate management.
3. Add these labels under each Deployment's `spec.template.metadata.labels`,
   replacing `inventory-service` with the corresponding application name:

   ```yaml
   app.kubernetes.io/name: inventory-service
   resilience4j-live-enabled: "true"
   resilience4j-live-profile: inventory-service
   ```

   Declare the JVM's container port as `name: jmx`, `containerPort: 9010`. Mount
   the JMX credentials and pass the JVM options in your deployment configuration.
   Declaring a container port does not start a JMX server.
4. Deploy one collector per cluster. Give it pod `get/list/watch` permission in
   the application namespaces, the retained profiles, JMX credentials/truststore,
   a stable cluster ID, and the hub's DNS name, trusted CA and enrollment token.
   The collector discovers pod addresses and UIDs from the Kubernetes API; no
   static pod IP list is needed. Allow collector-to-pod JMX traffic in network policies.
   Keep a collector fallback for applications that do not expose the optional timing MBean.
5. Enroll each cluster in the central hub and configure application-specific
   cross-cluster rollups. Allow outbound collector-to-hub gRPC/TLS connections.
   Size source limits for all replicas. New clusters need a collector and enrollment;
   scaling and pod replacement inside enrolled clusters are discovered automatically.
   Keep a hub fallback for applications that do not expose the optional timing MBean.
6. Generate representative requests using your existing testing tools. Verify all
   replicas appear, counters change, reporting coverage matches discovered pods,
   and per-application totals match member metrics. A request returning a fallback
   is not evidence of a successful protected call. Do not change retry/fallback
   behavior merely to populate dashboard counters.

Use the sibling dashboard repository's maintained configuration and deployment templates:

- [Multi-cluster guide](../../Resilience4jDashboard/docs/automatic-multicluster-runtime.md)
- [Secure remote JMX setup](../../Resilience4jDashboard/docs/jmx-collection.md)
- [Collector configuration](../../Resilience4jDashboard/config/collector/collector.example.json)
- [Collector RBAC](../../Resilience4jDashboard/config/kubernetes/collector-rbac.yaml)
- [Collector Deployment](../../Resilience4jDashboard/config/kubernetes/collector-deployment.yaml)
- [Hub configuration](../../Resilience4jDashboard/config/collector/hub.example.json)

Replace the example's placeholder MBean mappings with `profiles.json`. The retained
mappings target these applications' Micrometer/Resilience4j naming and tags; inspect
actual MBeans before using them with other applications or library versions.

JMX is sampled telemetry, not an exact event audit. Missing metrics cannot be invented,
aggregate latency percentiles require histogram support, and configuration suggestions
are estimates requiring workload validation. Memory estimates use manually entered
peak resident memory. Never commit generated keys, credentials, or runtime artifacts.
