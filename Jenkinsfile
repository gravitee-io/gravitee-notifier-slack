node ('Agent001') {
    def buildInfo

    stage('Prepare') {
        checkout scm
        def server = Artifactory.server 'sprint0-artifactory'
        def rtMaven = Artifactory.newMavenBuild()
        rtMaven.resolver server: server, releaseRepo: 'libs-release', snapshotRepo: 'libs-snapshot'
        rtMaven.deployer server: server, releaseRepo: 'private-releases', snapshotRepo: 'private-snapshots'
        rtMaven.tool = "mvn"
        rtMaven.run pom: 'pom.xml', goals: 'clean install', buildInfo: buildInfo
    }
}
