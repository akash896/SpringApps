#!/usr/bin/env sh
# Creates three local Kind clusters, loads local images, deploys, and verifies.
set -eu

script_dir="$(cd "$(dirname "$0")" && pwd)"
repo_dir="$(cd "${script_dir}/../.." && pwd)"

ensure_cluster() {
  cluster="$1"
  if kind get clusters | grep -qx "${cluster}"; then
    echo "Kind cluster ${cluster} already exists."
  else
    kind create cluster --name "${cluster}" --wait 90s
  fi
}

build_image() {
  service="$1"
  echo "Building Docker image ${service}:1.0.0..."
  docker build -t "${service}:1.0.0" "${repo_dir}/${service}"
}

load_image() {
  cluster="$1"
  service="$2"
  echo "Loading ${service}:1.0.0 into ${cluster}..."
  kind load docker-image "${service}:1.0.0" --name "${cluster}"
}

ensure_cluster cluster1
ensure_cluster cluster2
ensure_cluster cluster3

for service in pricing-service inventory-service order-service hystrix-dashboard; do
  build_image "${service}"
  load_image cluster1 "${service}"
  load_image cluster2 "${service}"
  load_image cluster3 "${service}"
done

sh "${script_dir}/deploy.sh" kind-cluster1 kind-cluster2 kind-cluster3
sh "${script_dir}/verify-replicas.sh" kind-cluster1 kind-cluster2 kind-cluster3
