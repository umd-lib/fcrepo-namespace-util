import os
import csv
import requests
import datetime
import sys
import time

# Define your base URL
base_url = os.environ.get("FCREPO_REST_ENDPOINT") or "http://fcrepo-local:8080/fcrepo/rest"

base_url = base_url.strip("/")

wait_seconds = os.environ.get("WAIT_SECONDS") or False

dry_run = os.environ.get("DRY_RUN").lower() == "true" if os.environ.get("DRY_RUN") else False

dry_run_str = "-dryrun" if dry_run else ""

# Define the SPARQL DELETE DATA template
sparql_template = 'DELETE DATA {{ <> a <{namespace_uri}None>}}'

# Get the authorization token from the environment variable
authorization_token = os.environ.get("AUTH_TOKEN")
if authorization_token is None:
    raise ValueError("Bearer token environment variable (AUTH_TOKEN) is missing.")

# Check if the input file path is provided as a command-line argument
if len(sys.argv) != 2:
    print("Usage: python script.py <input_csv_file>")
    sys.exit(1)

# Input CSV filename from command-line argument
input_csv_filename = sys.argv[1]

# Check if the input CSV file exists
if not os.path.isfile(input_csv_filename):
    print(f"Error: Input CSV file '{input_csv_filename}' not found.")
    sys.exit(1)

# Output CSV filename based on input CSV filename and timestamp
timestamp = datetime.datetime.now().strftime("%Y%m%d%H%M%S")
output_csv_filename = f"{os.path.splitext(input_csv_filename)[0]}-{timestamp}-patch-completed{dry_run_str}.csv"

# Open the input and output CSV files
with open(input_csv_filename, mode='r') as input_csv_file, open(output_csv_filename, mode='w', newline='') as output_csv_file:
    csv_reader = csv.DictReader(input_csv_file)
    fieldnames = ["Request URI", "SPARQL Data", "Response Code", "Request Time"]
    csv_writer = csv.DictWriter(output_csv_file, fieldnames=fieldnames)
    csv_writer.writeheader()

    # Prepare the PATCH request headers
    headers = {
        "Authorization": f"Bearer {authorization_token}",
        "Content-Type": "application/sparql-update"
    }

    for row in csv_reader:
        print(f"Processing row with prefix {row['namespace']}")
        timestamp = datetime.datetime.now().strftime("%Y%m%d%H%M%S")
        resource = row['resource']
        namespace_uri = row['namespaceUri']

        # Prepare the SPARQL DELETE DATA statement
        sparql_query = sparql_template.format(namespace_uri=namespace_uri)

        # Skip
        if resource is None or resource == "":
            print(f"  Skipping: {resource}")
            continue

        resource = resource.replace("jcr:content", "fcr:metadata")

        print(f"  Processing: {resource}")

        # Build the request URI
        request_uri = resource if resource.startswith("http") else f"{base_url}{resource}"
            

        # print(f"Requests Uri: {request_uri}")
        # print(f"    {sparql_query}")
        # next

        status = ""

        if not dry_run:
            # Send the PATCH request
            response = requests.patch(request_uri, data=sparql_query, headers=headers)
            status = response.status_code

        # Write the results to the output CSV file
        csv_writer.writerow({
            "Request URI": request_uri,
            "SPARQL Data": sparql_query,
            "Response Code": status,
            "Request Time": timestamp
        })

        if wait_seconds:
            print(f"  Pausing {wait_seconds} seconds")
            time.sleep(int(wait_seconds))

# Print a message to indicate the completion
print(f"Requests completed. Results written to {output_csv_filename}")
