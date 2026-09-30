# Onboard springRj4Apps to Resilience4j Live Dashboard

This guide explains the onboarding changes in branch `akash_dev_resilience4jDashboard`,
commit **`b5c2c4e7562678f28b19c2896fd887ad9ad57700`** —
“changes to on-board resilience4jDashboard” — and the deployment steps needed to connect
these applications to the dashboard.

The commit prepares the applications to expose useful JMX measurements and their requested
collection intervals. It does **not** install collectors, configure remote JMX in the existing
Kubernetes manifests, or deploy the central dashboard. Those are separate steps below.

## Contents

1. [Applications and expected topology](#1-applications-and-expected-topology)
2. [How measurements reach the dashboard](#2-how-measurements-reach-the-dashboard)
3. [Committed changes and their purpose](#3-committed-changes-and-their-purpose)
4. [Step-by-step deployment](#4-step-by-step-deployment)
5. [Verify the integration](#5-verify-the-integration)
6. [Read the results correctly](#6-read-the-results-correctly)
7. [Troubleshooting and limitations](#7-troubleshooting-and-limitations)
8. [Next steps and rollback](#8-next-steps-and-rollback)
9. [Instructions for a coding agent](#9-instructions-for-a-coding-agent)

Commands assume this repository is the current directory unless stated otherwise. Links to
`../Resilience4jDashboard/` assume both repositories are sibling checkouts. If they are located
elsewhere, use the corresponding path in your checkout. Domain names, registry names and
secret paths marked as examples must be replaced with deployment-specific values.

## 1. Applications and expected topology

These are Spring Boot **3.3.7**, Java **17**, Resilience4j **2.2.0** applications.
The REST call chain is:

```text
Client -> order-service -> inventory-service -> pricing-service
          HTTP 8081        HTTP 8082            HTTP 8083
```

| Application | Responsibility | Resilience component name | Bulkhead type |
| --- | --- | --- | --- |
| `pricing-service` | Calculate prices | `pricingApi` | Semaphore |
| `inventory-service` | Query pricing and supply stock availability | `pricingService` | Thread pool |
| `order-service` | Query inventory and accept/reject orders | `inventoryService` | Thread pool |

Each also uses a circuit breaker, retry and rate limiter. The component name describes the
protected operation; it is not necessarily the application name. For example, the
`inventoryService` circuit belongs to **order-service**. Filter by application first when
interpreting dashboard cards. These applications do not currently configure a TimeLimiter.

The checked-in [Kubernetes overlays](kubernetes/README.md) define:

| Application | cluster1 | cluster2 | cluster3 | Total pods |
| --- | ---: | ---: | ---: | ---: |
| pricing-service | 2 | 1 | 1 | 4 |
| inventory-service | 2 | 1 | 1 | 4 |
| order-service | 2 | 1 | 1 | 4 |
| All applications | 6 | 3 | 3 | **12** |

The application namespace is **`resilience-demo`**. Each replica runs in its own pod with
one application JVM. Monitoring this topology requires three collectors, one in each cluster,
and one central dashboard hub. If you change the replica counts, update validation expectations
and capacity accordingly; the collector discovers the actual opted-in pods.

## 2. How measurements reach the dashboard

```text
Inside each application pod
  Business calls -> existing Resilience4j registries
                         |
                         +-> native Micrometer meters
                         +-> supplemental event counters/gauges
                                      |
                                      v
                               JMX metric MBeans
                                      ^
                                      | authenticated TLS JMX/RMI reads
                                      |
Cluster Kubernetes API -------> per-cluster collector
  pod list/watch                 - discovers pods/IPs/UIDs
                                 - selects a metric profile
                                 - reads metrics and timing MBean
                                      |
                                      | persistent outbound gRPC/TLS stream
                                      v
                               central dashboard hub
                                 - validates inventory and freshness
                                 - calculates counter deltas/rates
                                 - aggregates by application/component/cluster
                                      |
                                      | persistent SSE stream
                                      v
                                   browser
                                 - renders metrics and replica coverage
                                 - derives optional one-minute suggestions
```

**Registries** own the actual Resilience4j components used by annotations and business calls.
A **meter registry** exports their measurements. A **JMX MBean** is a named management object
with readable attributes. A **profile** tells the collector which exact MBean attributes mean
success count, queue depth, circuit state and so on.

The application does not send one HTTP request per metric, and it does not create the browser
SSE stream. The collector reads JMX on its configured schedule. Collector-to-hub transport is
a persistent gRPC connection; the hub creates the SSE updates consumed by the browser.

Collectors obtain changing pod IPs and UIDs from their local Kubernetes API. The hub needs a
stable reachable DNS endpoint; it does not need a manually maintained pod or cluster IP list.
Every participating cluster must still have an installed collector and a matching hub enrollment.

## 3. Committed changes and their purpose

### 3.1 File-level change inventory

The onboarding commit modifies/adds 13 files:

| Files | Change | Reason |
| --- | --- | --- |
| [PricingApplication.java](pricing-service/src/main/java/com/example/pricing/PricingApplication.java), [InventoryApplication.java](inventory-service/src/main/java/com/example/inventory/InventoryApplication.java), [OrderApplication.java](order-service/src/main/java/com/example/order/OrderApplication.java) | Removed manually constructed `JmxMeterRegistry` beans and their imports | Let Spring Boot configure the JMX registry through its metrics configuration |
| [Pricing application.yml](pricing-service/src/main/resources/application.yml), [Inventory application.yml](inventory-service/src/main/resources/application.yml), [Order application.yml](order-service/src/main/resources/application.yml) | Corrected the Boot 3 JMX export property and added dashboard timing values | Enable the intended export configuration and keep interval ownership in each application |
| [Pricing metrics configuration](pricing-service/src/main/java/com/example/pricing/ResilienceMetricsConfiguration.java), [Inventory metrics configuration](inventory-service/src/main/java/com/example/inventory/ResilienceMetricsConfiguration.java), [Order metrics configuration](order-service/src/main/java/com/example/order/ResilienceMetricsConfiguration.java) | Added supplemental meters and a timing MBean | Supply measurements and interval metadata the collector needs |
| [observability/profiles.json](observability/profiles.json) | Added exact JMX mappings for all three applications | Translate application-specific MBean names into dashboard fields |
| [observability/README.md](observability/README.md) | Added a short integration reference | Explain the retained integration pieces and operational prerequisites |
| [README.md](README.md) | Described auto-configuration and linked observability instructions | Make the integration discoverable |
| [.gitignore](.gitignore) | Ignored `observability/.runtime/` | Keep generated local credentials/runtime artifacts out of normal Git tracking |

The commit does not change POM dependencies, business endpoints, fallback placement, retry
policies, existing load scripts, Dockerfiles or Kubernetes manifests. The required Actuator,
Resilience4j Micrometer and `micrometer-registry-jmx` dependencies were already present.
No dashboard Maven dependency was added to these applications.

### 3.2 JMX registry auto-configuration

Previously, each application created a registry explicitly:

```java
// Removed by the onboarding commit.
@Bean
JmxMeterRegistry jmxMeterRegistry() {
    return new JmxMeterRegistry(JmxConfig.DEFAULT, Clock.SYSTEM);
}
```

The applications now rely on Spring Boot's auto-configured registry. This centralizes registry
creation and applies Boot's export configuration rather than constructing it directly with
`JmxConfig.DEFAULT`. Removing this bean does not remove JMX metrics: the existing dependency
and corrected configuration enable Boot's registry.

The old property nesting was `management.metrics.export.jmx.enabled`. The committed Boot 3
configuration is the following fragment, present in all three `application.yml` files:

```yaml
management:
  jmx:
    metrics:
      export:
        enabled: true
  metrics:
    tags:
      application: ${spring.application.name}
```

The `application` tag was retained. It contributes to MBean names and lets profiles distinguish
applications. Changing `spring.application.name`, meter tags or naming conventions requires
reviewing the exact mappings. Enabling a JVM management port alone would expose JVM management
objects, not automatically create Resilience4j metric MBeans.

### 3.3 Supplemental metrics

Each new `ResilienceMetricsConfiguration.java` has the same implementation, with only its
package differing. It supplements the existing native meters; it does not create replacement
Resilience4j registries or execute business calls.

The `additionalResilienceMetrics` bean uses `SmartInitializingSingleton` to attach observers
after singleton initialization to the existing, YAML-defined components:

| Added meter name | Source of measurement | Dashboard purpose |
| --- | --- | --- |
| `application.resilience.circuit.duration.count` | Circuit success/error events | Number of timed protected executions |
| `application.resilience.circuit.duration.nanos` | Sum of event execution durations | Mean latency when paired with duration count |
| `application.resilience.circuit.rejected` | Circuit errors whose cause chain includes bulkhead or rate-limit rejection | Rejected subset of circuit failures |
| `application.resilience.circuit.timeouts` | Circuit errors containing recognized timeout exceptions | Timeout subset of circuit failures |
| `application.resilience.bulkhead.rejected` | Semaphore bulkhead rejection events | Rejected calls at the pricing bulkhead |
| `application.resilience.pool.rejected` | Thread-pool rejection events | Pool saturation/rejection evidence |
| `application.resilience.pool.completed` | Thread-pool call-finished events | Completed task counts and throughput |
| `application.resilience.pool.remaining` | Native remaining queue capacity gauge | Available queue capacity |
| `application.resilience.ratelimiter.rejected` | Rate limiter failure events | Rate-limited call counts |

Meters include a `name` tag for the component and the existing common `application` tag.
Circuit duration count/sum are updated for success and error events; ignored and denied calls
are not treated as timed executions here. A pool completion means the protected task finished,
not necessarily that its business result was successful.

The rejection classifier checks `BulkheadFullException` and `RequestNotPermitted`; timeout
classification checks `TimeoutException`, `SocketTimeoutException` and `HttpTimeoutException`.
It inspects up to eight causes. Other exception types are not automatically classified.
These circuit subcounts are populated only when that circuit observes the corresponding error;
annotation ordering and fallbacks affect which events are observed.

The duration profile converts nanoseconds to milliseconds with `scale: 0.000001`. The dashboard
uses changes in duration sums and counts to calculate mean execution latency. Counts and mean
latency cannot reconstruct latency percentiles.

This binder attaches once to fixed startup registry contents. If a future application creates
or replaces components dynamically, add lifecycle-aware binding before claiming their
supplemental metrics are covered. Do not attach duplicate listeners to the same component.

### 3.4 Application-owned refresh intervals

All three applications now contain:

```yaml
resilience4j:
  dashboard:
    pollIntervalMillis: 500
    browserPublishIntervalMillis: 750
```

The equivalent `application.properties` keys are:

```properties
resilience4j.dashboard.pollIntervalMillis=500
resilience4j.dashboard.browserPublishIntervalMillis=750
```

These are integration-owned properties, not built-in Resilience4j settings. The new
`dashboardTiming` bean reads their resolved values using Spring's `Environment`, then
registers a JDK `StandardMBean` implementing `DashboardTimingMXBean`:

| JMX contract | Value in these apps |
| --- | --- |
| ObjectName | `dev.resilience4j.live:type=DashboardConfiguration` |
| `PollIntervalMillis` attribute | Numeric integer `500` |
| `BrowserPublishIntervalMillis` attribute | Numeric integer `750` |

The registration closes during Spring shutdown. Nonpositive values fail validation; missing
required properties also fail startup. The collector reads this MBean once per JMX connection.
Restart/reconnect after changing these settings; editing a properties file alone does not
push an update to an already connected collector.

`500` requests a delay of 500 ms after a completed JMX poll. `750` requests the browser stream
publication interval. The fastest requested browser interval among live sources controls the
shared stream. Collector batching (`flushIntervalMillis`, normally 100 ms) is separate.
Neither value guarantees that every displayed measurement arrives within that duration.
JMX read time, batching, network delay and browser rendering add to observed latency.

The dashboard and collector remain Spring-independent: they read a standard JMX contract,
not a Spring configuration file. The Spring-specific code is confined to these applications'
property binding and bean lifecycle.

### 3.5 Mapping profiles

[observability/profiles.json](observability/profiles.json) is a **profiles object**, not a complete
collector configuration. Insert it under the collector's root `profiles` field.

| Profile key | Components mapped |
| --- | --- |
| `pricing-service` | `pricingApi`: circuit breaker, retry, rate limiter, semaphore bulkhead |
| `inventory-service` | `pricingService`: circuit breaker, retry, rate limiter, thread-pool bulkhead |
| `order-service` | `inventoryService`: circuit breaker, retry, rate limiter, thread-pool bulkhead |

For example, the pricing circuit's native success counter is mapped as:

```json
{
  "successfulCalls": {
    "objectName": "metrics:name=resilience4jCircuitbreakerCalls.application.pricing-service.kind.successful.name.pricingApi,type=timers",
    "attribute": "Count"
  }
}
```

This is a fragment inside a component's `metrics` object. `Count` is cumulative; the collector
and hub derive traffic rates from differences between samples. They must not use `MeanRate`
or rolling buffered-call gauges as substitutes for these counters.

Each circuit profile maps six one-hot state gauges and native failure/slow-call percentages.
Retry profiles map all four terminal outcome counters. Pool profiles combine native sizing,
active-thread and queue gauges with the added completion, rejection and remaining-capacity
meters. These profiles target this repository's actual application/component naming.

**Credential path alignment is required.** The committed profiles reference
`/run/metrics/jmx-credentials.json`. The dashboard's collector Deployment template instead
mounts credentials at `/run/resilience4j-live/`. Either mount credentials at the committed path
or change `credentialsFile` in your deployment copy of every profile. The example below uses
the template's mount path. Do not mount a file in one location and configure another.

## 4. Step-by-step deployment

### Step 1 — Check the branch and build the applications

From this repository root:

```bash
git branch --show-current
git log -1 --oneline
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
export PATH="$JAVA_HOME/bin:$PATH"
java -version
mvn -s "$PWD/.mvn/settings.xml" -f pricing-service/pom.xml clean verify
mvn -s "$PWD/.mvn/settings.xml" -f inventory-service/pom.xml clean verify
mvn -s "$PWD/.mvn/settings.xml" -f order-service/pom.xml clean verify
```

The Java selection commands are for macOS; elsewhere set `JAVA_HOME` to the installed JDK 17.
Use your organization's approved Maven settings if it requires a private artifact mirror.
Expect successful builds/tests before deployment. Build/publish application images through the
existing [Kubernetes workflow](kubernetes/README.md); onboarding does not replace that workflow.

### Step 2 — Verify local MBeans before enabling remote access

Start the applications through the existing local workflow if validating locally. Inspect them
with JConsole or your management tool. Confirm the `metrics` domain contains both native
Resilience4j meters and the added `applicationResilience...` meters.

Check the timing MBean's two attributes are numeric 500 and 750. Invoke existing endpoints and
confirm the corresponding metric counters change. Verify the exact ObjectNames in
`observability/profiles.json`; one configured unreadable attribute rejects that source's poll.
A healthy Actuator endpoint alone does not prove that these JMX attributes exist.

### Step 3 — Enable authenticated TLS remote JMX in each pod

The checked-in Dockerfiles launch `java -jar /app/app.jar`, and the existing Kubernetes
manifests expose only business HTTP ports. They currently do not start remote JMX.

Prepare these application-side files through your platform's secret mechanism:

| Example path | Contents |
| --- | --- |
| `/run/jmx/jmxremote.access` | `monitor readonly` |
| `/run/jmx/jmxremote.password` | The same account and its generated password, in Java properties format |
| `/run/jmx/server.p12` | Application JVM server key/certificate chain |
| `/run/jmx/jvm.options` | The JVM options below, with the real keystore password |

Example protected Java argument file:

```text
-Dcom.sun.management.jmxremote.port=9010
-Dcom.sun.management.jmxremote.rmi.port=9011
-Dcom.sun.management.jmxremote.authenticate=true
-Dcom.sun.management.jmxremote.ssl=true
-Dcom.sun.management.jmxremote.registry.ssl=true
-Dcom.sun.management.jmxremote.access.file=/run/jmx/jmxremote.access
-Dcom.sun.management.jmxremote.password.file=/run/jmx/jmxremote.password
-Djavax.net.ssl.keyStore=/run/jmx/server.p12
-Djavax.net.ssl.keyStoreType=PKCS12
-Djavax.net.ssl.keyStorePassword=REPLACE_WITH_KEYSTORE_PASSWORD
```

The JVM password file must be owned by the application user and accessible only to that user,
for example mode `0600`. A group-readable Secret mount can fail the JVM's permission check;
use your platform's secret delivery or an init container/private volume to set ownership.

The following is a **container fragment**, to merge into each existing container, preserving
its image, business port, existing environment variables, probes, resources and volume mounts:

```yaml
command: ["java"]
args:
  - "@/run/jmx/jvm.options"
  - "-Djava.rmi.server.hostname=$(POD_IP)"
  - "-jar"
  - "/app/app.jar"
env:
  - name: POD_IP
    valueFrom:
      fieldRef:
        fieldPath: status.podIP
ports:
  - name: jmx
    containerPort: 9010
  - name: jmx-rmi
    containerPort: 9011
```

This overrides the existing image entrypoint deliberately to insert JVM options before `-jar`.
Merge the two ports into the existing ports list; retain `http`. For inventory/order, also retain
`PRICING_SERVICE_URL`/`INVENTORY_SERVICE_URL`. Mount the prepared files at `/run/jmx`.
Both JMX ports must reach the same pod; do not route them through a load-balanced business Service.

Issue certificates/trust material for the advertised endpoints through your platform PKI.
If mutual TLS is required, add the application's client-CA truststore and collector client
keystore settings. Full security guidance is in the dashboard's
[application integration guide](../Resilience4jDashboard/docs/application-integration.md#2-enable-secure-remote-jmx).

### Step 4 — Opt each application into discovery

Merge these labels under each Deployment's `spec.template.metadata.labels`, changing both
application/profile values to the matching service name:

```yaml
app.kubernetes.io/name: pricing-service
resilience4j-live-enabled: "true"
resilience4j-live-profile: pricing-service
```

Use `inventory-service` and `order-service` for their deployments. The existing application
label is retained; the two `resilience4j-live-*` labels are deployment work still required.
The profile name must exactly match a key in `observability/profiles.json`.

The collector looks for one named `jmx` port per pod. Kubernetes supplies the pod UID and
current IP. Collector identity includes cluster, namespace, application and pod UID/container;
applications do not need to invent or manually maintain `POD_UUID` values.

### Step 5 — Build the dashboard distribution and image

From the sibling dashboard repository:

```bash
cd ../Resilience4jDashboard
./mvnw clean verify
docker build -f config/collector/Dockerfile -t your-registry/resilience4j-live:0.1.0 .
docker push your-registry/resilience4j-live:0.1.0
```

Select JDK 17 in this terminal as well. The produced
`dashboard-server/target/dashboard-server.jar` contains both the hub and collector entry
points. The hub and each collector run as separate processes, outside the application JVMs.
Return to the application repository for the following configuration example.

### Step 6 — Create one collector configuration per cluster

Start with the dashboard's collector template, then insert this repository's real profiles.
This example generates a private `cluster1` deployment copy and aligns credential paths:

```bash
cd ../springRj4Apps
mkdir -p observability/.runtime/cluster1
python3 - <<'PY'
import json
from pathlib import Path

configuration = json.loads(Path(
    "../Resilience4jDashboard/config/collector/collector.example.json").read_text())
configuration.update(
    clusterId="cluster1",
    environment="production",
    namespaces=["resilience-demo"],
    hubHost="dashboard.management.example",
    sourceLimit=64,
    pollIntervalMillis=500,
    flushIntervalMillis=100,
)
configuration["profiles"] = json.loads(Path("observability/profiles.json").read_text())
for profile in configuration["profiles"].values():
    profile["credentialsFile"] = "/run/resilience4j-live/jmx-credentials.json"
Path("observability/.runtime/cluster1/collector.json").write_text(
    json.dumps(configuration, indent=2) + "\n")
PY
```

Replace the example hub DNS before deployment. The `production` environment is an example
identity, not a requirement to deploy to production; choose the actual environment consistently
in collectors, hub enrollments and rollups. Repeat for `cluster2` and `cluster3`, changing both
the identity and output directory. Kubernetes context names can differ from these logical IDs.

Prepare the collector Secret files expected by the template:

- `hub-token`: this cluster's enrollment token.
- `hub-ca.crt`: CA for the hub's gRPC endpoint.
- `jmx-credentials.json`: `{"username":"monitor","password":"ACTUAL_SECRET"}`.
- `jmx-truststore.p12`: trusted application JMX CAs.
- `jvm.options`: JSSE truststore settings, including its protected password.

The collector argument file uses mounted absolute paths:

```text
-Djavax.net.ssl.trustStore=/run/resilience4j-live/jmx-truststore.p12
-Djavax.net.ssl.trustStoreType=PKCS12
-Djavax.net.ssl.trustStorePassword=REPLACE_WITH_TRUSTSTORE_PASSWORD
```

If applications use different JMX accounts, mount separate credential files and update each
profile accordingly. Do not put secrets in `JAVA_TOOL_OPTIONS`, which Java prints at startup.

### Step 7 — Configure and start the hub

Copy [hub.example.json](../Resilience4jDashboard/config/collector/hub.example.json) into private
deployment configuration. The template uses `east`/`west` and namespace `shop`; replace these
with this deployment's identities instead of applying it unchanged:

- Enroll `cluster1`, `cluster2`, `cluster3`, each with the chosen environment and namespace
  `resilience-demo`, using a different token file for each cluster.
- Set the hub `sourceLimit` to cover all 12 pods plus rollout headroom; 64 is adequate for
  this inventory, subject to component/display limits.
- Set browser fallback `browserPublishIntervalMillis` to 750 if desired. The applications'
  timing MBeans already request 750 while they are live.
- Mount the hub's PEM certificate/private key and token files at the paths in the configuration.
- Configure explicit rollups if you want combined views of each application across clusters.

Example rollup entry inside root `rollups`:

```json
{
  "name": "all-clusters-pricing",
  "environment": "production",
  "namespace": "resilience-demo",
  "serviceName": "pricing-service",
  "clusters": ["cluster1", "cluster2", "cluster3"]
}
```

Add equivalent entries for inventory and order with their actual application names. Rollups
combine the same application across selected clusters; they do not combine the three
applications into one circuit or one total business-request count.

On the hub host, with the distribution and configuration installed:

```bash
java -jar dashboard-server/target/dashboard-server.jar \
  --config /etc/resilience4j-live/hub.json
```

The template's collector listener uses gRPC/TLS port 9443. Its browser listener is loopback
9099. Use an authorized tunnel/private access, or configure protected remote browser access
as described in [dashboard security](../Resilience4jDashboard/docs/security.md). Opening a
browser to port 9443 will not display the dashboard.

### Step 8 — Install collectors and deploy the application changes

Use the dashboard's [collector deployment procedure](../Resilience4jDashboard/docs/automatic-multicluster-runtime.md#4-install-one-collector-per-cluster)
for ConfigMap/Secret creation and rollout commands. Adapt its templates as follows:

| Template setting | Value for this repository |
| --- | --- |
| Application namespace in collector config, Role, RoleBinding and enrollment | `resilience-demo`, replacing example `shop` |
| Collector namespace | `observability`, unless your platform chooses another |
| Collector image | Your published dashboard/collector image |
| Collector config | The corresponding cluster's generated JSON |
| JMX credentials mount | `/run/resilience4j-live/jmx-credentials.json` in the normalized example |
| Collector replicas | One active collector per cluster |
| Application replicas | Existing overlays: 2/1/1 per application across cluster1/2/3 |

Grant only Pod get/list/watch in monitored namespaces. Each collector uses its local
service-account API token/CA, not a manually maintained list of pod IPs. Allow collector-to-pod
9010/9011, collector-to-hub 9443, Kubernetes API and DNS traffic. Proxies must support streaming
HTTP/2 for gRPC and unbuffered long-lived SSE for the browser.

Deploy application image/configuration changes using the existing overlay workflow after
merging JMX and label changes. Installing collectors alone does not add JMX settings to pods.
A successful Kubernetes rollout confirms availability, not accurate metric collection.

## 5. Verify the integration

### 5.1 Check topology and collector discovery

From this repository, using actual context names:

```bash
CONTEXT1=your-cluster1-context
CONTEXT2=your-cluster2-context
CONTEXT3=your-cluster3-context
sh kubernetes/scripts/verify-replicas.sh "$CONTEXT1" "$CONTEXT2" "$CONTEXT3"
```

For each context, inspect opted-in pods and collector logs:

```bash
CONTEXT=your-cluster1-context
kubectl --context "$CONTEXT" -n resilience-demo get pods -l resilience4j-live-enabled=true -o wide
kubectl --context "$CONTEXT" -n observability logs deployment/resilience4j-collector --tail=100
```

Expect six application sources in cluster1, three in cluster2 and three in cluster3 when all
configured pods exist and are eligible. The central view should show three collectors, all
three applications and 12 distinct instances. Readiness and JMX reachability are separate.

### 5.2 Check JSON, SSE and the browser

Through the local listener or authorized tunnel:

```bash
curl --fail --silent --show-error http://127.0.0.1:9099/api/v2/state
curl --no-buffer --fail http://127.0.0.1:9099/api/v2/live
```

The SSE command remains open and prints successive updates; stop it with Ctrl+C. Open
[the dashboard](http://127.0.0.1:9099/resilience4j-live/) and clear filters. Check that every
application appears, sources become `LIVE`, and reporting coverage matches expected membership.
After two successful polls, counter rates should establish a baseline.

### 5.3 Run normal and heavy traffic

The existing scripts target **localhost ports 8081, 8082 and 8083**. They do not discover
clusters or pods and they do not connect to the dashboard. In an approved local/load-test setup,
make those endpoints reach the intended applications before running:

```bash
python3 loadTest.py
```

Stop normal load with Ctrl+C, then separately run:

```bash
python3 heavyLoadTest.py --workers 100
```

Normal load uses six workers. Heavy load defaults to 100 and permits `--workers` overrides.
Both run until interrupted and print HTTP status summaries. Do not run heavy load on production
without authorization.

For multi-cluster validation, route these requests through an approved test router spanning
the desired replicas, or deliberately test each cluster/pod with appropriate routing. A single
`kubectl port-forward service/...` usually selects one backing pod and is not proof that every
replica received traffic. Existing scripts require endpoint adaptation or external routing to
exercise all clusters; their unchanged localhost targets cannot provide that by themselves.

### 5.4 Compare values and exercise recovery

| Check | Expected evidence |
| --- | --- |
| Per-pod accuracy | Mapped JMX counters and the same dashboard instance agree after allowing collection delay |
| Traffic coverage | Each intended replica has changing counters; idle pods are identified rather than assumed tested |
| Aggregation | Application/component totals correspond to contributing replicas; other applications remain separate |
| Cross-cluster view | A rollup includes the selected clusters for that application only |
| Overload | Rejection/queue/rate-limit measurements respond to actual events, with any missing data explicit |
| Pod replacement | New UID appears and rate baselines reset without editing pod addresses |
| Collector interruption | Coverage becomes partial/stale, not healthy zero or silently reduced membership |
| Recovery | Collector reconnects, inventory is restored/reconciled and rates reestablish |
| Guidance | After sufficient uninterrupted browser observations, suggestions show their evidence |

Run disruptive checks only in a disposable or otherwise approved environment. Record actual
source coverage, stale/discovery warnings and omissions. A green HTTP response can be a fallback;
compare against the protected component's outcomes, not just the load script's status counts.

## 6. Read the results correctly

**Host** is a per-instance rate, or the mean per reporting instance in an aggregate. **Group**
is the combined rate for that application/component. For four reporting pricing replicas at
10 calls/s each, the application rollup's Group is 40 calls/s and Host is 10 calls/s.

Cards are identified by application, component category/name, cluster and view. Similar names
across cards are expected: a circuit, retry, limiter and bulkhead can protect the same operation.
Their counts cannot be added as unique business requests. A request can also traverse all three
services in the REST chain.

`0` means measured zero; an unavailable marker means insufficient/missing telemetry. Native
failure percentages and dashboard sampled aggregate rates can use different observation windows.
JMX sampling can miss a brief circuit transition. This adapter does not reconstruct percentiles
from counters or means.

**Suggested settings** uses one-minute browser observations, not an automatic server-side tuner.
It needs sufficient activity and measurement coverage. Memory estimates require manually entered
representative peak RSS per replica. Retry advice needs actual retried outcomes; idle or
insufficient evidence is not a valid prediction. Suggestions are load-test candidates, not
promises of optimal configuration or zero downtime. See
[dashboard calculations](../Resilience4jDashboard/docs/dashboard.md#suggested-settings).

## 7. Troubleshooting and limitations

| Symptom | First checks |
| --- | --- |
| No applications in dashboard | Hub configured/enrolled, collectors running, pod labels and namespace grants correct |
| Pods discovered but metrics unavailable | JMX startup flags, both ports, credentials mount path, TLS trust and exact profile |
| `JMX_MAPPING_ERROR` | Missing/wrong MBean attribute, changed tags, incompatible state gauges or invalid timing MBean |
| JMX connection cannot reach the JVM | RMI advertised pod address, fixed connector port, network policy and TLS |
| JVM refuses password file | Owner-only permissions and correct application ownership |
| 500/750 ms properties have no effect | Timing MBean exists with numeric attributes; restart/reconnect after changes |
| Only one replica's metrics change | Test routing may select one pod; localhost scripts do not distribute across clusters |
| Retry/rejection subset stays zero | Inspect actual event/fallback/annotation ordering; do not change semantics just to populate a card |
| Suggested settings is waiting | Observation duration, activity, fresh sources and required gauges/counters |

The supplemental binder assumes fixed startup components. JMX reads across several MBeans
are not one atomic transaction. Cumulative counts reset with meters/JVMs; reconnects or resets
need new rate baselines. The dashboard retains live state in memory rather than durable history.

The generated `observability/.runtime/` directory is ignored, not a secure vault. Protect file
permissions and use managed secrets. Git ignore rules cannot remove a secret already committed.

## 8. Next steps and rollback

The onboarding commit completed application instrumentation, interval exposure and mapping
profiles. To finish a deployment:

1. Verify the three applications' MBeans against the committed profiles.
2. Provide remote JMX credentials/certificates and merge the JVM, port, label and mount changes
   into deployment configuration for every application.
3. Create three collector configurations with real profiles and consistent credential paths.
4. Enroll the three clusters, choose explicit application rollups and provide hub TLS/routing.
5. Deploy one collector per cluster; verify all 12 application instances for the current overlays.
6. Run approved normal/heavy traffic and compare per-instance/aggregate measurements.
7. Test replacement/reconnection, document missing measurements, and qualify load/rotation in
   the target environment before operational reliance.

This document was prepared from the named commit and repository files. It does not claim that
these deployment steps or load checks were executed while writing the guide.

To pause collection in one cluster without stopping business applications:

```bash
kubectl --context "$CONTEXT" -n observability scale deployment/resilience4j-collector --replicas=0
```

Scale it back to one to resume. Stop foreground load scripts/tunnels with Ctrl+C. Roll back
collector/hub configurations and images through the deployment system if needed. If withdrawing
an application, remove its opt-in labels and disable remote JMX only if no other monitoring
consumer needs it. Do not delete shared namespaces or change business policies to remove monitoring.

## 9. Instructions for a coding agent

Give the agent this file plus the dashboard's
[agent integration playbook](../Resilience4jDashboard/docs/agent-integration.md).
The important repository-specific facts are:

- Onboarding baseline: branch `akash_dev_resilience4jDashboard`, commit `b5c2c4e`.
- Reuse the three existing `ResilienceMetricsConfiguration` classes; do not register another
  timing MBean or duplicate listeners. Dependencies already exist.
- Profiles are in `observability/profiles.json`; insert them under collector `profiles`.
- Align `/run/metrics/jmx-credentials.json` with the deployment's actual Secret mount.
- Namespace is `resilience-demo`, not the dashboard templates' `shop`.
- Logical example clusters are `cluster1`, `cluster2`, `cluster3`; context names are supplied
  by the operator. Current overlays yield 12 application pods, not 18.
- Keep 500 ms polling and 750 ms browser intervals application-owned. Do not hardcode them
  in dashboard Java or change resilience policies/fallback placement.
- The existing manifests still need remote JMX and opt-in wiring. Explain and review the
  rendered manifests before claiming onboarding is deployed.
- Use the existing traffic scripts with deliberate routing; document which replicas received
  traffic. Distinguish local fixtures, live-cluster validation and unperformed checks.
- Preserve unrelated changes; leave credentials and temporary orchestration outside tracked files.

For deeper reference, use the dashboard's [documentation index](../Resilience4jDashboard/docs/index.md),
[JMX mapping guide](../Resilience4jDashboard/docs/jmx-collection.md),
[configuration reference](../Resilience4jDashboard/docs/configuration.md) and
[multi-cluster deployment guide](../Resilience4jDashboard/docs/automatic-multicluster-runtime.md).
