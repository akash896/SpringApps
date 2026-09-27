#!/usr/bin/env sh
# Creates the three local Kind clusters, deploys the services, and verifies replicas.
set -eu

script_dir="$(cd "$(dirname "$0")" && pwd)"
repository_dir="$(cd "${script_dir}/../.." && pwd)"

ensure_cluster() {
  cluster_name="$1"
  if kind get clusters | grep -qx "${cluster_name}"; then
    echo "Kind cluster ${cluster_name} already exists."
  else
    kind create cluster --name "${cluster_name}" --wait 90s
  fi
}

build_image() {
  service_name="$1"
  echo "Building ${service_name}:1.0.0..."
  docker build --tag "${service_name}:1.0.0" "${repository_dir}/${service_name}"
}

load_image() {
  cluster_name="$1"
  for service_name in pricing-service inventory-service order-service; do
    kind load docker-image "${service_name}:1.0.0" --name "${cluster_name}"
  done
}

ensure_cluster cluster1
ensure_cluster cluster2
ensure_cluster cluster3

build_image pricing-service
build_image inventory-service
build_image order-service

load_image cluster1
load_image cluster2
load_image cluster3

sh "${script_dir}/deploy.sh" kind-cluster1 kind-cluster2 kind-cluster3
sh "${script_dir}/verify-replicas.sh" kind-cluster1 kind-cluster2 kind-cluster3
