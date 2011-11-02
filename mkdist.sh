#!/bin/bash

BUILD_DATE=$(date +"%F %H:%M %Z")
VERSION="4.2"

if [[ -z $KODKOD_HOME ]]
then
    KODKOD_HOME=../relations-experimental
fi

function compile {
    version_file=src/edu/mit/csail/sdg/alloy4/Version.java
    cp -r $version_file $version_file.bak
    sed -i \
      -e 's/public static String buildDate.*/public static String buildDate() { return "'"$BUILD_DATE"'"; }/' \
      -e 's/public static String version.*/public static String version() { return "'"$VERSION"'"; }/' $version_file

    echo "[cleaning up the bin folder...]"
    rm -rf bin/*

    CP=$KODKOD_HOME/bin:$(ls -1 lib/*.jar | xargs | sed 's/\ /:/g')
    echo "[compiling...]"
    find src -name "*.java" | xargs javac -cp $CP -d bin -target 1.5

    mv $version_file.bak $version_file
}

function dist {
    DST=dist

    rm -rf $DST/alloy*
    mkdir -p $DST/alloy

    for f in lib/*jar
    do
	unzip -o $f -d $DST/alloy
    done
    
    rm -rf bin/tmp
    cp -r bin/* $DST/alloy/
    rm -rf $DST/alloy/kodkod
    cp -r $KODKOD_HOME/bin/kodkod $DST/alloy/kodkod
    
    rm -rf $DST/alloy/META-INF
    
    mkdir -p $DST/alloy/META-INF
    cp MANIFEST.MF $DST/alloy/META-INF

    # DEPRECATED    
    # for d in META-INF # amd64-linux  help  icons  images  LICENSES  META-INF  models  README.TXT  x86-freebsd  x86-linux  x86-mac  x86-windows
    # do
    # 	cp -r template/$d alloy/
    # done

    pushd $(pwd)
    cd $DST/alloy
    find -type f -name "*.java" | xargs rm -f
    zip -r alloy-dev.jar *
    chmod +x alloy-dev.jar
    mv alloy-dev.jar ../
    popd
}

if [[ "X"$1 == "X" ]]
then
  compile 
  dist
else
  $1
fi

# echo '#!/bin/bash
    
# CP=classes:$(ls -1 lib/*.jar | xargs | sed "s/\ /:/g")
# java -Xms512m -Xmx2048m -ea -cp $CP edu.mit.csail.sdg.alloy4whole.SimpleGUI
# ' > $DST/alloy/run-alloy.sh
    
# chmod +x $DST/alloy/run-alloy.sh
    