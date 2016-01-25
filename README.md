CarabiServer is a multiuser backend framework. it's main feature is connecting
to different "non-kernel" databases for running any SQL and PL/SQL queries.
(Today only Oracle DBMS is supported.) CarabiServer can be adapted to business logick inside the database and
can be used to write business logick above the database. One client can work with several databases at the same time.

CarabiServer also can work without Oracle (but Oracle JDBC is still necessary for compiling today). Main Oracle independent feature is corporative chat.

CarabiServer is written in Java EE and is figured for running on GlassFish 4 with the PostgreSQL kernel database.
To build and run the project:

Prepare tools and dependences:
* Install [Java Development Kit](http://www.oracle.com/technetwork/java/javase/downloads/index.html).
* Install [Apache Maven](http://maven.apache.org/). If you use IDE (such as [NetBeans](https://netbeans.org/)), it can contain Maven inside.
* Download and unzip [GlassFish 4](https://glassfish.java.net/download.html).
* Install [PostgreSQL](http://www.postgresql.org/). If you use OS GNU/Linux, installing PostgreSQL from repository is recommended.
* Download [Oracle JDBC](http://www.oracle.com/technetwork/database/jdbc-112010-090769.html).
* Download [PostgreSQL JDBC](https://jdbc.postgresql.org/).
and clients. Use `mvn compile assembly:single` to build a non-dependent jar.

Prepare sources:
* Clone this repository by Git
* Clone [SOAP Stub](../carabiserver_stub) by Git.
* Clone [libraries](../carabiserver_libs) by Git.
* Clone [Eventer](../eventer) by Git if you need it.

Prepare environment:
* Start GlassFish 4 and PostgreSQL.
* Create the database, run carabiserver_web/src/main/sql/*.sql files
* Create JDBC connection pools and JDBC resources named 'jdbc/carabikernel' and 'jdbc/carabichat' to connect to the database.
Use web interface (Admin Console) or command line.
First pool should contain Init SQL property 'set SEARCH_PATH to CARABI_KERNEL' and the second 'set SEARCH_PATH to CARABI_CHAT'.
* Copy JDBC jars to glassfish4/glassfish/lib
* Add path to Oracle Instantclient to JVM native-library-path parameter if you will use jdbc:oracle:oci connection strings.

It is recommended to backup file glassfish4/glassfish/domains/domain1/config/domain.xml after setup.


Build:
* Run `mvn install` in stub and libs. At first time it can be a long procedure, Maven will download plugins and librares.
* Oracle JDBC can not be redistrebuted under license, so you have to add it manually, Create "com/oracle/ojdbc6/11.2.0"
directory in the repository and put there file ojdbc6-11.2.0.jar (rename ojdbc6.jar from the driver), and ojdbc6-11.2.0.pom
with the following code:
```
<?xml version="1.0" encoding="UTF-8"?>  
<project xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd" xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.oracle</groupId>
  <artifactId>ojdbc6</artifactId>
  <version>11.2.0</version>
</project>
```
* Run `mvn install` in carabiserver project

Run:
* Deploy carabiserver/target/carabiserver*.war using web interface (Admin Console) or command line.

