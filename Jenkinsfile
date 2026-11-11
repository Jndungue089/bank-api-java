pipeline {
  agent any
  options { timestamps(); timeout(time: 20, unit: 'MINUTES') }
  stages {
    stage('Build & Unit/Integration tests') {
      steps { sh './mvnw -B -ntp verify' }
      post { always { junit 'target/surefire-reports/*.xml' } }
    }
    stage('Dependency vulnerability scan') {
      steps { sh './mvnw -B -ntp org.owasp:dependency-check-maven:check -DfailBuildOnCVSS=7 || true' }
    }
    stage('Package image') {
      when { branch 'main' }
      steps { sh 'docker build -t sb24-api:${BUILD_NUMBER} .' }
    }
  }
}
