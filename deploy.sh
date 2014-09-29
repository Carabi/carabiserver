for war in target/*.war
do
	asadmin undeploy ${war%.war}
	mvn install
	asadmin deploy $war
done
