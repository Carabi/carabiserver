mkdir -p target/javadoc
javadoc -d target/javadoc -charset utf-8 -sourcepath src/main/java -overview src/main/java/overview.html ru.carabi.server ru.carabi.server.kernel ru.carabi.server.kernel.oracle ru.carabi.server.soap ru.carabi.server.rest ru.carabi.server.servlet ru.carabi.server.entities
cp -R target/javadoc /var/www/html/netbeans
