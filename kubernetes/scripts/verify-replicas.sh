#!/usr/bin/env sh
# Verifies ready Deployment replicas and the aggregate four-replica target per app.
set -eu

namespace="resilience-demo"

if [ "$#" -ne 3 ]; then
  echo "Usage: $0 <cluster1-context> <cluster2-context> <cluster3-context>" >&2
  exit 2
fi

check_cluster() {
  cluster_name="$1"
  context="$2"
  expected="$3"

  printf '%s (%s):\n' "${cluster_name}" "${context}"
  for app in pricing-service inventory-service order-service; do
    desired="$(kubectl --context "${context}" --namespace "${namespace}" \
      get deployment "${app}" -o jsonpath='{.spec.replicas}')"
    available="$(kubectl --context "${context}" --namespace "${namespace}" \
      get deployment "${app}" -o jsonpath='{.status.availableReplicas}')"
    available="${available:-0}"

    printf '  %-18s desired=%s available=%s expected=%s\n' \
      "${app}" "${desired}" "${available}" "${expected}"

    if [ "${desired}" -ne "${expected}" ] || [ "${available}" -ne "${expected}" ]; then
      echo "Replica verification failed for ${app} in ${cluster_name}." >&2
      return 1
    fi
  done
}

check_cluster cluster1 "$1" 2
check_cluster cluster2 "$2" 1
check_cluster cluster3 "$3" 1

echo "Verified: each application has 4 ready replicas across cluster1, cluster2, and cluster3."
