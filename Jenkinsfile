node() {
    def mvnArgs = ["-U", "-P gravitee-report", "clean", "install"]

    stage "Checkout"
    checkout scm

    stage "Build"

    def mvnHome = tool 'MVN33'
    def javaHome = tool 'JDK 8'
    withEnv(["PATH+MAVEN=${mvnHome}/bin",
             "JAVA_HOME=${javaHome}"]) {
        def mvnCommamd = ["${mvnHome}/bin/mvn"] + mvnArgs
        sh "${mvnCommamd.join(" ")}"
        try {
            sh "ls **/target/surefire-reports/TEST-*.xml"
            step([$class: 'JUnitResultArchiver', testResults: '**/target/surefire-reports/TEST-*.xml'])
        } catch (Exception ex) {
            echo "No tests to archive"
        }
        try {
            sh "ls target/surefire-reports/TEST-*.xml"
            step([$class: 'JUnitResultArchiver', testResults: 'target/surefire-reports/TEST-*.xml'])
        } catch (Exception ex) {
            echo "No tests to archive"
        }

        stage("SonarQube analysis") {
            withSonarQubeEnv('SonarQube') {
                sh "${mvnHome}/bin/mvn sonar:sonar"
            }
        }
    }

    archiveArtifacts artifacts: 'target/gravitee-notifier-slack-*.zip', onlyIfSuccessful: true
}