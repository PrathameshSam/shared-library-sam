@groovy.transform.Field
private final pushed = []

def call(Map config = [:], Map defaults = [:]) {
  final defaultConfig = [ file: './Dockerfile', os: 'linux', arch: 'amd64' ]
  config = defaultConfig + defaults + config

  final registry = getContainerRegistry(config)

  def candidates = []
  def tags = []
  if (config.tags) {
    config.tags.each {
      final tagIdx = it.indexOf ':'
      def name, tag
      if (tagIdx == -1) {
        name = config.APP_NAME
        tag = it
      } else {
        name = it.take tagIdx
        tag = it.substring tagIdx + 1
      }

      tags << "$registry.name/$name:$tag"
    }
  } else {
    final version = config.APP_VERSION ?: '0.0.0'
    final tagIdx = config.file.indexOf '-'
    if (tagIdx != -1) {
      final tag = config.file.substring tagIdx + 1
      candidates << "$version-$tag"
      if (config.APP_IS_STABLE) {
        candidates << tag
      }
    }
    candidates << version
    if (config.APP_IS_STABLE) {
      candidates << 'latest'
    }
    candidates = candidates.collect { "$registry.name/$config.APP_NAME:$it" }
  }

  final image = (tags.find() ?: candidates.find()).split ':'
  final props = [ DOCKER_REPOSITORY: image[0], DOCKER_TAG: image[1] ]

  tags += (candidates - pushed)
  if (tags) {
    final path = config.path ?: config.file.take(config.file.lastIndexOf('/')) ?: '.'
    withContainerRegistry(registry) {
      ansiColor('xterm') {
        withArtifactoryEnv(cli: false) {
          final userFile = sh(returnStdout: true, script: '''#!/bin/sh -e
              mktemp
          ''').trim()
          final passwordFile = sh(returnStdout: true, script: '''#!/bin/sh -e
              mktemp
          ''').trim()
          try {
            sh """#!/bin/sh -e
                printf "\$ARTIFACTORY_USER" > $userFile
                printf "\$ARTIFACTORY_PASSWORD" > $passwordFile

                docker build ${tags.collect { " -t $it" }.sum()} \
                    --cache-from $props.DOCKER_REPOSITORY \
                    --build-arg "ARTIFACTORY_URL=\$ARTIFACTORY_URL" \
                    --build-arg 'BIN_DIR=${getBinaryDirectory config}' \
                    --build-arg 'BUILDKIT_INLINE_CACHE=1' \
                    --build-arg 'COMMIT=$config.GIT_COMMIT' \
                    --secret id=artifactory_user,src=$userFile \
                    --secret id=artifactory_password,src=$passwordFile \
                    --label 'commit=$config.GIT_COMMIT' \
                    --file '$config.file' \
                    $path
            """
          } finally {
            sh """#!/bin/sh -e
                rm -f $passwordFile && rm -f $userFile
            """
          }
        }
      }
      tags.each {
        sh "docker push $it"
      }
    }
    pushed += tags
  }

  // Using "docker inspect" owing to https://github.com/moby/moby/issues/29901
  final repoDigest = sh(returnStdout: true, script: """#!/bin/sh -e
      docker inspect --format='{{index .RepoDigests 0}}' $props.DOCKER_REPOSITORY:$props.DOCKER_TAG
  """).trim()

  props + [ DOCKER_DIGEST: repoDigest.substring(1 + repoDigest.lastIndexOf('@')) ]
}