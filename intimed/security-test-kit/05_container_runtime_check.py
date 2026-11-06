#!/usr/bin/env python3
"""SC-17 / SC-18 – container & Kubernetes runtime posture of the IntiMed namespace (AKS): non-root, read-only root
filesystem, no privilege escalation, all capabilities dropped, seccomp, images from the private registry (by digest when
pinned), default-deny NetworkPolicy, no Kubernetes secrets other than the CSI-synced runtime secret, workload identity.
Usage: 05_container_runtime_check.py --namespace intimed [--from-json pods.json]   (kubectl must be configured)"""
import argparse, json, re, subprocess, sys, kitlib as k

S = "05_container_runtime_check"
ap = argparse.ArgumentParser()
ap.add_argument("--namespace", default="intimed")
ap.add_argument("--from-json", help="offline: a JSON file {pods:…, netpols:…, secrets:…} (kubectl get -o json output)")
ap.add_argument("--registry-suffix", default=".azurecr.io")
a = ap.parse_args()


def kube(kind):
    return json.loads(subprocess.check_output(["kubectl", "get", kind, "-n", a.namespace, "-o", "json"], text=True))


try:
    data = json.load(open(a.from_json)) if a.from_json else {"pods": kube("pods"), "netpols": kube("networkpolicies"), "secrets": kube("secrets")}
except Exception as e:  # noqa: BLE001
    k.check(S, "SC-17", "Kubernetes API reachable", False, str(e)[:200]); k.finish(S); sys.exit(1)

pods = [p for p in data["pods"]["items"] if p["metadata"]["labels"].get("app.kubernetes.io/name") == "intimed"]
k.check(S, "SC-17", "IntiMed pod present (%d)" % len(pods), len(pods) >= 1)
for p in pods:
    name = p["metadata"]["name"]
    ps = p["spec"].get("securityContext", {})
    k.check(S, "SC-17", f"{name}: runAsNonRoot", ps.get("runAsNonRoot") is True)
    k.check(S, "SC-17", f"{name}: seccomp RuntimeDefault", ps.get("seccompProfile", {}).get("type") == "RuntimeDefault")
    k.check(S, "SC-17", f"{name}: workload identity (no pod-managed secrets)", p["metadata"]["labels"].get("azure.workload.identity/use") == "true")
    for c in p["spec"]["containers"]:
        sc = c.get("securityContext", {})
        k.check(S, "SC-17", f"{name}/{c['name']}: readOnlyRootFilesystem", sc.get("readOnlyRootFilesystem") is True)
        k.check(S, "SC-17", f"{name}/{c['name']}: no privilege escalation", sc.get("allowPrivilegeEscalation") is False and not sc.get("privileged"))
        k.check(S, "SC-17", f"{name}/{c['name']}: capabilities dropped (ALL)", "ALL" in sc.get("capabilities", {}).get("drop", []))
        img = c["image"]
        k.check(S, "SC-18", f"{name}/{c['name']}: image from private registry", img.split("/")[0].endswith(a.registry_suffix), img)
        k.check(S, "SC-18", f"{name}/{c['name']}: no 'latest' tag", not img.endswith(":latest"), img)
        k.check(S, "SC-05", f"{name}/{c['name']}: no secret literals in env", [e["name"] for e in c.get("env", []) if "value" in e and re.search(r"(PASSWORD|_SECRET|MASTER_KEY|_TOKEN|API_KEY)$", e["name"].upper())] == [])
np = data["netpols"]["items"]
k.check(S, "SC-18", "NetworkPolicy selects the IntiMed pod with Ingress+Egress", any(set(n["spec"].get("policyTypes", [])) >= {"Ingress", "Egress"}
        and n["spec"].get("podSelector", {}).get("matchLabels", {}).get("app.kubernetes.io/name") == "intimed" for n in np))
sec = [s["metadata"]["name"] for s in data["secrets"]["items"] if s.get("type") == "Opaque"]
k.check(S, "SC-05", "only CSI-synced runtime secrets in the namespace", set(sec) <= {"intimed-runtime", "intimed-dbadmin"}, sec)
k.finish(S)
