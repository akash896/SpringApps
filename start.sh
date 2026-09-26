#!/usr/bin/env sh
# Builds and starts Pricing, Inventory, and Order services. Press Ctrl-C to stop all of them.
set -eu

script_dir="$(cd "$(dirname "$0")" && pwd)"
log_dir="${script_dir}/logs"
maven_settings="${script_dir}/.mvn/settings.xml"
maven_cache="${script_dir}/.maven-cache"
mkdir -p "${log_dir}"

pids=""

start_service() {
  local service_name="$1"
  local service_dir="${script_dir}/${service_name}"
  local log_file="${log_dir}/${service_name}.log"

  echo "Starting ${service_name}; log: ${log_file}"
  (
    exec java -jar "${service_dir}/target/${service_name}-1.0.0.jar"
  ) >"${log_file}" 2>&1 &
  pids="${pids} $!"
}

build_service() {
  local service_name="$1"
  local service_dir="${script_dir}/${service_name}"

  echo "Building ${service_name}..."
  (
    cd "${service_dir}"
    mvn --settings "${maven_settings}" --batch-mode --no-transfer-progress \
      "-Dmaven.repo.local=${maven_cache}" -DskipTests package
  )
}

stop_services() {
  [ -n "${pids}" ] || return 0
  echo
  echo "Stopping services..."
  for pid in ${pids}; do
    kill "${pid}" 2>/dev/null || true
  done
  wait ${pids} 2>/dev/null || true
}

trap stop_services EXIT INT TERM

# Build sequentially so the shared project-local Maven cache is never written
# by concurrent Maven processes. The local settings bypass an unavailable
# machine-wide Artifactory configuration.
build_service pricing-service
build_service inventory-service
build_service order-service

# Start in dependency order. The Resilience4j fallbacks tolerate downstream
# services that are still initializing.
start_service pricing-service
start_service inventory-service
start_service order-service

echo "Services are starting:"
echo "  Order:     http://localhost:8081"
echo "  Inventory: http://localhost:8082"
echo "  Pricing:   http://localhost:8083"
echo "Use Ctrl-C to stop all services."
wait ${pids}
