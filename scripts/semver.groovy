@Library('jenkins@v4') _
import groovy.json.JsonOutput

properties([
    parameters([
        string(name: 'repository', description: 'Source code location'),
        string(name: 'branch', defaultValue: 'main', description: 'Original source code branch'),
        string(name: 'url', description: 'Page describing the release'),
        string(name: 'commit', description: 'Source code commit'),
    ]),
    pipelineTriggers([[
        $class: 'GenericTrigger',
        genericVariables: [
            [ key: 'repository', value: '$.pull_request.base.repo.url' ],
            [ key: 'branch', value: '$.pull_request.base.repo.url' ],
            [ key: 'url', value: '$.pull_request.html_url' ],
            [ key: 'action', value: '$.action' ],
            [ key: 'merged', value: '$.pull_request.merged' ],
            [ key: 'commit', value: '$.pull_request.merge_commit_sha' ],
            [ key: 'buildPipeline', value: '$.pull_request.base.repo.name' ],
        ],
        causeString: '$url',
        token: 'semver-assign',
        regexpFilterText: '$action:$merged',
        regexpFilterExpression: 'closed:true'
    ]])
])

podTemplates.simple {
  node(POD_LABEL) {
    container('builder') {
      createRelease repository, branch, commit
    }
  }
}

private void createRelease(String repository, String branch, String commit) {
  repository = new URI(repository).getPath() - '.git'

  final releases = new URL("https://github.rakops.com/api/v3/repos$repository/releases")

  withCredentials([string(credentialsId: 'rcp-ci-secret', variable: 'TOKEN')]) {
    // Shared libraries can use Groovy HTTP methods; those methods are not
    // permitted in scripts, so command-line utilities must be used instead.
    final detail = readJSON text: sh(returnStdout: true, script: """#!/bin/sh -e
        curl -ksS -H 'Authorization: token $TOKEN' $releases/latest
    """)

    final versions = detail.name ? (detail.name - 'v').split('[.]') : [ '0', '0', '0' ]

    final releaseType = branch.split('/')[0]
    if (releaseType == 'patch' || releaseType == 'hotfix') {
      patch = versions[2] as Integer
      versions[2] = (patch + 1).toString()
    } else if (releaseType == 'minor' || versions[0] == '0' && releaseType != 'major') {
      minor = versions[1] as Integer
      versions[1] = (minor + 1).toString()
      versions[2] = '0'
    } else {
      major = versions[0] as Integer
      versions[0] = (major + 1).toString()
      versions[1] = '0'
      versions[2] = '0'
    }

    for (rc = 0; true; rc++) {
      final name = "v${versions[0]}.${versions[1]}.${versions[2]}-rc$rc"
      final payload = JsonOutput.toJson([
        tag_name: name,
        target_commitish: commit ?: branch,
        name: name,
        prerelease: true
      ])

      final response = readJSON text: sh(returnStdout: true, script: """#!/bin/sh -e
          curl -ksS \
              -H 'Authorization: token $TOKEN' \
              -H 'Content-Type: application/json' \
              -d '$payload' \
              '$releases'
      """)

      if (response.errors.any { it.code == 'already_exists' }) {
        continue
      }

      if (response.errors) {
        error "Creating $name failed"
      }

      echo "Created $name by merging $releaseType into $detail.name"

      return
    }
  }
}