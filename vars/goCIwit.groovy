#!/usr/bin/groovy
import io.fabric8.Fabric8Commands
def call(body) {
    // evaluate the body block, and collect configuration into the object
    def config = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()


    def ghOrg =  config.githubOrganisation
    def dockerOrg = config.dockerOrganisation
    def prj = config.project
    def buildOptions = config.dockerBuildOptions ?: ''
    def makeTarget = config.makeTarget ?: ''
    def dockerfileBuilder = config.dockerfileBuilder

    def flow = new Fabric8Commands()
    def version
    def imageName

    if (!ghOrg){
        error 'no github organisation defined'
    }
    if (!dockerOrg){
        error 'no docker organisation defined'
    }
    if (!prj){
        error 'no project defined'
    }

    def token
    try {
        withCredentials([usernamePassword(credentialsId: 'cd-github', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
            token = env.PASS
        }
    } catch (err){
        echo 'no cd-github credentials so will default to using a secret or annonymous'
    }

    def buildPath = "/home/jenkins/go/src/github.com/${ghOrg}/${prj}"

    sh "mkdir -p ${buildPath}"

    dir(buildPath) {
        checkout scm

        container(name: 'go') {
            if (!flow.isAuthorCollaborator(token)){
                error 'Change author is not a collaborator on the project, failing build until we support the [test] comment'
            }

            stage ('build binary'){
                version = "SNAPSHOT-${env.BRANCH_NAME}-${env.BUILD_NUMBER}"
                sh "make ${makeTarget}"
            }

            stage ('check go format binary'){
                version = "SNAPSHOT-${env.BRANCH_NAME}-${env.BUILD_NUMBER}"
                sh "make check-go-format"
            }

            stage ('run unit tests'){
                version = "SNAPSHOT-${env.BRANCH_NAME}-${env.BUILD_NUMBER}"
                sh "make test-unit"
            }
        }

        container(name: 'docker') {
            imageName = "docker.io/${dockerOrg}/${prj}"
            dockerContainerName = "${version}-core"
            dockerDbContainerName = "${version}-postgresql"
            dockerImageDb = "registry.centos.org/postgresql/postgresql:9.6"
            gopathInContainer = "/tmp/go"
            packageName = "github.com/${ghOrg}/${prj}"
            packagePath = "${gopathInContainer}/src/${packageName}"

            stage ('build snapshot image'){
                sh "docker build -t ${imageName}:${version} ${buildOptions} ."
                sh "docker build -t ${imageName}-builder:${version} ${dockerfileBuilder} ."
            }

            stage ('push snapshot image'){
                sh "docker run --detach=true -t ${DOCKER_RUN_INTERACTIVE_SWITCH} --name=\"${dockerContainerName}\" -e GOPATH=${gopathInContainer}	-w ${packagePath} ${imageName}-builder:${version}"
            }

            stage ('run postgreSQL container'){
                sh "docker run --detach=true -p 5432:5432 --name=\"${dockerDbContainerName}\" -e POSTGRESQL_ADMIN_PASSWORD=mysecretpassword ${dockerImageDb}"
            }

            stage ('run integration tests'){
              sh "docker exec -t \"${dockerContainerName}\" bash -ec 'make test-integration'"
            }

            stage ('push snapshot image'){
                sh "docker push ${imageName}:${version}"
            }
        }

        stage('notify'){
            def changeAuthor = env.CHANGE_AUTHOR
            if (!changeAuthor){
                error "no commit author found so cannot comment on PR"
            }
            def pr = env.CHANGE_ID
            if (!pr){
                error "no pull request number found so cannot comment on PR"
            }
            def message = "@${changeAuthor} snapshot ${prj} image is available for testing.  `docker pull ${imageName}:${version}`"
            container('docker'){
                flow.addCommentToPullRequest(message, pr, "${ghOrg}/${prj}")
            }
        }
    }
    return version
  }
