import os
import csv
import requests
import datetime
import sys
import pysolr
import time

solr_url = os.environ.get("SOLR_URL") or "http://localhost:8983/solr/fedora4/"

wait_seconds = os.environ.get("WAIT_SECONDS") or False

# Define the SPARQL DELETE DATA template
rdf_type_template = '{namespace_uri}None'

# solr query template
query_template = 'rdf_type:"{value}"'

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

# Initialize solr
solr = pysolr.Solr(solr_url, always_commit=True)

def find_resources_in_solr(rdf_type):
    query = query_template.format(value=rdf_type)
    results = solr.search(query, fl='id,rdf_type')

    # Extract the values of the 'id' from the results
    matching_ids = []
    for result in results:
        uri = result['id']
        if "fedora:Binary" in result['rdf_type']:
            uri += "/fcr:metadata"
        matching_ids.append(uri)

    return matching_ids

def main():
    # Output CSV filename based on input CSV filename and timestamp
    timestamp = datetime.datetime.now().strftime("%Y%m%d%H%M%S")
    output_csv_filename = f"{os.path.splitext(input_csv_filename)[0]}-with-resources.csv"

    # Open the input and output CSV files
    with open(input_csv_filename, mode='r') as input_csv_file, open(output_csv_filename, mode='w', newline='') as output_csv_file:
        csv_reader = csv.DictReader(input_csv_file)
        csv_writer = csv.DictWriter(output_csv_file, fieldnames=csv_reader.fieldnames)
        csv_writer.writeheader()
        rows_without_resource = []

        for row in csv_reader:
            print(f"Processing row with prefix {row['namespace']}")
            row_written=False
            if (row['resource'] is None or row['resource'] == ""):
                rdf_type = rdf_type_template.format(namespace_uri=row['namespaceUri'])
                resource_uris = find_resources_in_solr(rdf_type)
                for uri in resource_uris:
                    row['resource'] = uri
                    csv_writer.writerow(row)
                    row_written=True
                if not row_written:
                    rows_without_resource.append(row)
            else:
                csv_writer.writerow(row)

            if wait_seconds:
                print(f"  Pausing {wait_seconds} seconds")
                time.sleep(int(wait_seconds))

        for row in rows_without_resource:
            csv_writer.writerow(row)

    # Print a message to indicate the completion
    print(f"Updated CSV file written to {output_csv_filename}")

if __name__ == "__main__":
    main()