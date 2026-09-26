#!/usr/bin/env sh
# Applies the requested replica distribution to the three supplied contexts.
set -eu

if [ "$#" -ne 3 ]; then
  echo "Usage: $0 <cluster1-context> <cluster2-context> <cluster3-context>" >&2
  exit 2
fi

script_dir="$(cd "$(dirname "$0")" && pwd)"
kubernetes_dir="$(cd "${script_dir}/.." && pwd)"

apply_overlay() {
  cluster_name="$1"
  context="$2"

  echo "Applying ${cluster_name} manifests to Kubernetes context '${context}'..."
  kubectl --context "${context}" apply -k "${kubernetes_dir}/overlays/${cluster_name}"
}

apply_overlay cluster1 "$1"
apply_overlay cluster2 "$2"
apply_overlay cluster3 "$3"
