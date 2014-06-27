#!/usr/bin/env bash

dir=`dirname "${BASH_SOURCE-$0}"`
dir=`cd "$dir"; pwd`
script=`basename $0`
user=$USER
epoch=`date +%s`

function usage() {
  echo "Application lifecycle management tool for the Purchase application."
  echo "Usage: $script --action <deploy|start|run|stop|status> [--host <hostname>]"
  echo ""
  echo "  Options"
  echo "    --action    Specifies the action to be taken on the application. Use 'run' to run the map/reduce."
  echo "    --host      Specifies the host that Reactor is running on. (Default: localhost)"
  echo "    --help      This help message"
  echo ""
}

function deploy_action() {
  local app=$1; shift;
  local jar=$1; shift;
  local host=$1; shift;

  echo "Deploying application $app..."
  status=`curl -o /dev/null -sL -w "%{http_code}\\n" -H "X-Archive-Name: $app" -X POST http://$host:10000/v2/apps --data-binary @"$jar"`
  if [ $status -ne 200 ]; then
    echo "Failed to deploy app"
    exit 1;
  fi

  echo "Deployed."
}

function program_action() {
  local app=$1; shift;
  local program=$1; shift;
  local type=$1; shift;
  local action=$1; shift;
  local host=$1; shift

  http="-X POST"
  if [ "x$action" == "xstatus" ]; then
    http=""
  fi

  types="$type";
  if [ "$type" != "mapreduce" ]; then
    types="${type}s";
  fi

  if [ "x$action" == "xstatus" ]; then
    echo -n " - Status for $type $program: "
  else
    maction="$(tr '[:lower:]' '[:upper:]' <<< ${action:0:1})${action:1}"
    echo " - ${maction/Stop/Stopp}ing $type $program... "
  fi

  status=$(curl -s $http http://$host:10000/v2/apps/$app/$types/$program/$action 2>/dev/null)

  if [ $? -ne 0 ]; then
   echo "Action '$action' failed."
  else
    if [ "x$action" == "xstatus" ]; then
      echo $status
    fi
  fi
}

if [ $# -lt 1 ]; then
  usage
  exit 1
fi

host="localhost"
action=
while [ $# -gt 0 ]
do
  case "$1" in
    --action) shift; action="$1"; shift;;
    --host) shift; host="$1"; shift;;
    *)  usage; exit 1
  esac
done


if [ "x$action" == "x" ]; then
  usage
  echo
  echo "Action not specified."
fi

app="PurchaseHistory"

if [ "x$action" == "xdeploy" ]; then
  jar_path=`ls $dir/../target/Purchase-*.jar`
  deploy_action $app $jar_path $host
elif [ "x$action" == "xrun" ]; then
  program_action $app "PurchaseHistoryWorkflow_PurchaseHistoryBuilder" "mapreduce" "start" $host
elif [ "x$action" == "xstatus" ]; then
  program_action $app "PurchaseFlow" "flow" $action $host
  program_action $app "PurchaseProcedure" "procedure" $action $host
  program_action $app "PurchaseHistoryWorkflow_PurchaseHistoryBuilder" "mapreduce" $action $host
else
  program_action $app "PurchaseFlow" "flow" $action $host
  program_action $app "PurchaseProcedure" "procedure" $action $host
fi
