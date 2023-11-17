# Cleaning up spurious nodetypes and namespaces

This document has instructions to clean up the spurious nodetypes and nodetypes
that were accidentally created by a bug in the plastron batch loader program.

## Problem

A bug in batchloader program caused spurous namespaces and nodetypes to be
created in the repository. These nodetypes and namespaces had a specific
pattern:

- Spurious namespace prefixes had the following name pattern: nsXXX (Where XXX
  is a three)
- Spurious namespace URIs contain the string 'tx:' in them.
- Spurious node types had the following name pattern: nsXXX:None (Where XXX is a
  three)

These spurious nodetypes and namespaces were contributing to increased latency
in processing requests.

## Solution

The Namespace Utility was refactored to find and clean up these nodetypes and
namespaces. The clean up process involves the following steps in the specified
order:

1. Use the Namespace Utility 'list' command to list out the spurious namespaces
   and nodetypes into a csv file.
2. Use the find-resources-with-nodetypes.py find associated resources for the
   nodetypes identified in step 1 from the solr index.
3. Use the Namespace Utility 'add-resources' command to find associated
    resources using JCR query for the nodetypes that did not have an associated
    resource in the solr index. a. Note: This is exponentially slower that step
    2 when the number of nodetypes are large, so we use this only for nodetypes
    from
4. Sort the nodetypes list to ensure the namespace URIs with overlapping paths
   are cleaned up in the correct order.
5. Create a backup of the modeshape database.
6. Use the nodetype-cleanup-patch.py to patch the resources to remove the
   references to the spurious nodetypes.
7. Create a backup of the modeshape database.
8. Use the Namespace Utility 'clean' command with 'nodetype' mode to unregister
    the spurious nodetypes. a. Note: This is a very slow process as the JCR
    query is used to confirm that there are no associated resources before
    unregistering a nodetype, and the JCR queries are very slow when there are
    large number of nodetypes.
9. Create a backup of the modeshape database.
10. Use the Namespace Utility 'clean' command with 'namespace' mode to
    unregister the spurious namespaces. a. Note: The namespace cleanup process
    would not proceed if any spurious nodetype exists within the repository.
    This is to prevent repository startup failures due to nodetypes with missing
    namespaces.

## Running

Here are the steps to use the utility to perform the cleanup.

```sh
# Running the list command
java \
    -Dfcrepo.home=/var/umd-fcrepo-webapp \
    -Dfcrepo.modeshape.configuration=file:/tmp/repository.json \
    -Dcommand=list \
    -Dskip.resources=true \
    -Dfilepath=/path/to/file.csv \
    -jar fcrepo-namespace-util.jar

# Running the python script to add resources
#  Set SOLR_URL env to override default solr base url
#  Set WAIT_SECONDS env to configure delay between requests
python scripts/find-resources-with-nodetypes.py /path/to/file.csv

# Running the add-resources command
java \
    -Dfcrepo.home=/var/umd-fcrepo-webapp \
    -Dfcrepo.modeshape.configuration=file:/tmp/repository.json \
    -Dcommand=add-resources  \
    -Dfilepath=/path/to/file.csv \
    -jar fcrepo-namespace-util.jar

# Reverse sort by namespace uri to prevent parent uri cleanup before child path.
sort -t ',' -k 2 -r  /path/to/file.csv > /path/to/sorted-file.csv

# Backup the db
pg_dump -U fcrepo fcrepo_modeshape5 > /path/to/dump-pre-patch.sql

# Running the patch python script
#  Set FCREPO_REST_ENDPOINT env to override default fcrepo base url
#  Set WAIT_SECONDS env to configure delay between requests
python scripts/nodetype-cleanup-patch.py /path/to/sorted-file.csv

# Review the patch output
grep -c 204 /path/to/sorted-file-*-completed.csv

# Backup the db
pg_dump -U fcrepo fcrepo_modeshape5 > /path/to/dump-pre-nodetype-cleanup.sql

# Running the clean command to unregister nodetype
java \
    -Dfcrepo.home=/var/umd-fcrepo-webapp \
    -Dfcrepo.modeshape.configuration=file:/tmp/repository.json \
    -Dcommand=clean \
    -Dclean.mode=nodetype \
    -Dfilepath=/path/to/sorted-file.csv \
    -jar fcrepo-namespace-util.jar

# Backup the db
pg_dump -U fcrepo fcrepo_modeshape5 > /path/to/dump-pre-namespace-cleanup.sql

java \
    -Dfcrepo.home=/var/umd-fcrepo-webapp \
    -Dfcrepo.modeshape.configuration=file:/tmp/repository.json \
    -Dcommand=clean \
    -Dclean.mode=namespace \
    -Dfilepath=/path/to/sorted-file.csv \
    -jar fcrepo-namespace-util.jar

```

## Building Docker Images

To faciliate running these clean up tools as kubernetes jobs, the following
instructions can be used to build and deploy docker images with these tools.

```sh
# Building the Java utility image
docker buildx build --builder kube --platform linux/amd64,linux/arm64 --push -f Dockerfile -t docker.lib.umd.edu/fcrepo-namespace-util:<VERSION> .

# Building the python scripts image
docker buildx build --builder kube --platform linux/amd64,linux/arm64 --push -f Dockerfile.scripts -t docker.lib.umd.edu/fcrepo-namespace-util-scripts:<VERSION> .
```

**Note**: Use the version in pom.xml as the tag when building the images. For
SNAPSHOT versions, the 'latest' tag.
