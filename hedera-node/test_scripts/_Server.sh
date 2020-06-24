#!/bin/bash
#
####################################
#
# AWS instance config
#
# regionList, list AWS regions where to start spot instances
# numberOfInstancesPerRegion, list of numbers that corresponds to the number of instances to start in each region
# spotPricePerRegion, list of maximum prices for spot instances that correspond to each region
#
####################################
#              Virgina     Oregon        Canada      São Paulo       Sydney        Frankfurt         Seoul            Tokyo
#regionList=("us-east-1" "us-west-2" "ca-central-1" "sa-east-1" "ap-southeast-2" "eu-central-1" "ap-northeast-2" "ap-northeast-1")
regionList=(  "us-east-1" )
numberOfRegions=${#regionList[@]}
#numberOfInstancesPerRegion=(4 4 4 4 4 4 4 4)
numberOfInstancesPerRegion=( 4  )
totalInstances=0
for i in ${numberOfInstancesPerRegion[@]}; do
  let totalInstances+=$i
done
awsInstanceType="m4.10xlarge"


####################################
#
# Experiement config
#
# desc               a description of the experiment which will be part of the filename
# experimentDuration number of seconds to run each experiment
# delayList          number of milliseconds the caller should pause after each sync
# bytesPerTransList  number of bytes per transaction
# transPerEventList  number of transactions per event
# syncCallersList    max number of syncs this will initiate and be in simultaneously
# useTLSList         use TLS or not
# additionalSettings what to include in settings.txt, this will be the same for every experiment
#
# an experiment is run for every combination of choosing one number from each list.
#
####################################

desc="${totalInstances} mem, ${numberOfRegions} region file creation"
#desc="${#regionList[@]} mem, ${numberOfInstancesPerRegion} region"
#desc="5 mem, 3 region - CPU Signature Test"
experimentDuration=2400000
delayList=(0)
#bytesPerTransList=(100 1024 4096)
#transPerEventList=(1024)
#transPerSecList=(1000 10000 50000)
#syncCallersList=(1 2 3 4)
bytesPerTransList=(100)
transPerEventList=(-1)
let "bytesToTest=1024*1024*5"
let "calculatedTrans=$bytesToTest/$bytesPerTransList"
transPerSecList=( 3 )
echo $bytesToTest $transPerSecList
syncCallersList=( 3)
useTLSList=(1)
throttle7extraList=(0.5)
verifyEventSigsList=(1)
multiSocketNumberList=(1)
multiSocketBytesList=(1460)
additionalSettings=(
  "numConnections, 1000"
  "throttle7, 1"
  "throttle7threshold, 1.5"
  "useRSA, 1" 
  "lockLogThreadDump, 0"
  "lockLogTimeout, 4000"
  "lockLogBlockTimeout, 4000"
  "showInternalStats, 1"
  "forceCpuDigest, 1"
  "forceCpuVerification, 1"
  "cpuVerifierBatchSize, 50000"
  "cpuDigestBatchSize, 50000"
  "cpuVerifierThreadRatio, 0.50"
  "cpuDigestThreadRatio, 0.50"
  "cpuVerifierQueueSize, 100"
  "cpuDigestQueueSize, 100"
  "gpuDigestBatchSize, 61440"
  "gpuVerifierBatchSize, 61440"
  "csvFileName, platform"
  "transactionMaxBytes, 6144"
  "roundsExpired, 500"
  "lockLogBlockTimeout, 2000"
  "maxEventQueueForCons, 1000"
  "maxOutgoingSyncs, 1"
  "doUpnp, 0"
  "useLoopbackIp, 0"
  "checkSignedStateFromDisk"
  "enableEventStreaming, 1"
  )
  
# the script to run on the remote node
testScriptToRun="runAllExperiments.sh"
# the time in seconds that takes the above script to finish
getNumberOfExperiments()
{
  echo $(( ${#syncCallersList[@]} * ${#delayList[@]} * ${#bytesPerTransList[@]} * ${#transPerEventList[@]} * ${#useTLSList[@]} * ${#transPerSecList[@]} * ${#throttle7extraList[@]} * ${#verifyEventSigsList[@]} * ${#multiSocketNumberList[@]} * ${#multiSocketBytesList[@]} ))
}
scriptRunningTime=$(( (experimentDuration+2)*`getNumberOfExperiments` ))
# the jar that will be used for this test
appJarToUse="HederaNode.jar"
# AWS AMI to use
# awsAmi="service-jprofiler11"
awsAmi="ATF-U18.04-OJDK12.0.1-PSQL10.12-BADGERIZE-V13"

sshUsername="ubuntu"
