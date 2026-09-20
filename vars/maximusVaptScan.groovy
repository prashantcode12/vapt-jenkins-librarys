def call(Map config = [:]) {

    String serverUrl = (config.vaptServerUrl ?: 'https://pt.maximusatlas.com').trim()
    String apiKey = config.apiKey
    String target = config.target
    String branch = (config.branch ?: '').trim()
    String pipelineRunId = (config.pipelineRunId ?: '').trim()

    List scanTypes = config.scanTypes ?: ['deps', 'sast', 'secrets']

    if (!apiKey) {
        error "maximusVaptScan requires 'apiKey'."
    }

    if (!target) {
        error "maximusVaptScan requires 'target'."
    }

    echo "========================================"
    echo "Maximus VAPT / Code Scan"
    echo "Target : ${target}"
    echo "Server : ${serverUrl}"
    echo "========================================"

    String pythonScript = """
import sys
import time
import json
import urllib.request
import urllib.error
import os
import urllib.parse

server_url = sys.argv[1].rstrip("/")
api_key = os.environ["VAPT_API_KEY"]
target = sys.argv[2]
branch = sys.argv[3]
pipeline_run_id = sys.argv[4]
scan_types = json.loads(sys.argv[5])


def request_api(url, method="GET", data=None):

    headers = {
        "X-API-Key": api_key,
        "Accept": "application/json"
    }

    body = None

    if data is not None:
        headers["Content-Type"] = "application/json"
        body = json.dumps(data).encode("utf-8")

    request = urllib.request.Request(
        url,
        data=body,
        headers=headers,
        method=method
    )

    try:
        return urllib.request.urlopen(request, timeout=60)

    except urllib.error.HTTPError as e:

        response_body = e.read().decode("utf-8", errors="replace")

        raise RuntimeError(
            f"HTTP {e.code} from {url}: {response_body}"
        )


# --------------------------------------------------
# START SCAN
# --------------------------------------------------

trigger_url = f"{server_url}/api/ci/code-scan/start"

payload = {
    "source": target,
    "scan_types": scan_types,
    "github_token": "",
    "branch": branch,
    "pipeline_run_id": pipeline_run_id
}

print(f"Starting Code Scan...")
print(f"Endpoint: {trigger_url}")
print(f"Target: {target}")

response = request_api(
    trigger_url,
    method="POST",
    data=payload
)

response_data = json.loads(response.read().decode("utf-8"))

job_id = response_data.get("job_id")

if not job_id:
    raise RuntimeError(
        f"VAPT server did not return job_id: {response_data}"
    )

print(f"Scan started successfully.")
print(f"Job ID: {job_id}")


# --------------------------------------------------
# POLL STATUS
# --------------------------------------------------

status_url = f"{server_url}/api/ci/code-scan/status/{urllib.parse.quote(job_id, safe='')}"

print("Polling scan status...")

while True:

    time.sleep(10)

    response = request_api(status_url)

    status_data = json.loads(
        response.read().decode("utf-8")
    )

    status = status_data.get("status")

    print(f"Current Status: {status}")

    normalized_status = str(status).lower()

    if normalized_status in [
        "completed",
        "complete",
        "success",
        "successful",
        "finished",
        "failed",
        "error",
        "cancelled",
        "canceled"
    ]:
        break


if normalized_status in [
    "failed",
    "error",
    "cancelled",
    "canceled"
]:
    raise RuntimeError(
        f"VAPT Code Scan failed. Final status: {status}"
    )


# --------------------------------------------------
# DOWNLOAD REPORTS
# --------------------------------------------------

os.makedirs("vapt_reports", exist_ok=True)

repo_name = target.rstrip("/").split("/")[-1] or "repository"

if repo_name.endswith(".git"):
    repo_name = repo_name[:-4]

encoded_repo = urllib.parse.quote(repo_name)
encoded_branch = urllib.parse.quote(branch or "unknown_branch")
encoded_build = urllib.parse.quote(
    pipeline_run_id or os.environ.get("BUILD_NUMBER", "000")
)


html_url = (
    f"{server_url}/api/ci/code-scan/report/{job_id}/html"
    f"?repo_name={encoded_repo}"
    f"&branch={encoded_branch}"
    f"&build_id={encoded_build}"
)

excel_url = (
    f"{server_url}/api/ci/code-scan/report/{job_id}/excel"
    f"?repo_name={encoded_repo}"
    f"&branch={encoded_branch}"
    f"&build_id={encoded_build}"
)


print("Downloading HTML report...")

response = request_api(html_url)

with open(
    "vapt_reports/Maximus_VAPT_Report.html",
    "wb"
) as report:
    report.write(response.read())


print("Downloading Excel report...")

response = request_api(excel_url)

with open(
    "vapt_reports/Maximus_VAPT_Report.xlsx",
    "wb"
) as report:
    report.write(response.read())


print("========================================")
print("VAPT Code Scan completed successfully.")
print("Reports downloaded successfully.")
print("========================================")
"""


    writeFile(
        file: 'vapt_runner.py',
        text: pythonScript
    )


    withEnv([
        "VAPT_API_KEY=${apiKey}"
    ]) {

        sh(
            label: 'Run Maximus VAPT Scan',
            script: """
                python3 vapt_runner.py \
                    '${serverUrl}' \
                    '${target}' \
                    '${branch}' \
                    '${pipelineRunId}' \
                    '${groovy.json.JsonOutput.toJson(scanTypes)}'
            """
        )
    }


    archiveArtifacts(
        artifacts: 'vapt_reports/*.*',
        allowEmptyArchive: false
    )


    echo "========================================"
    echo "Maximus VAPT Scan finished."
    echo "Reports archived in Jenkins."
    echo "========================================"
}