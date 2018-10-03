#!/bin/bash -e
DAX_NAMESPACE=${DAX_NAMESPACE:-'dax-dev'}

kubectl create secret generic albuquery-config --from-file=./albuquery.yml --namespace $DAX_NAMESPACE