import groovy.transform.Field

import org.codehaus.groovy.runtime.StackTraceUtils
import org.csanchez.jenkins.plugins.kubernetes.pod.retention.Always;
import org.csanchez.jenkins.plugins.kubernetes.pod.retention.Never;
import org.csanchez.jenkins.plugins.kubernetes.pod.retention.OnFailure;
import org.csanchez.jenkins.plugins.kubernetes.pod.retention.PodRetention;

@Field final DOCKER_VERSION = '20.10'
@Field final GCLOUD_VERSION = '337.0.0'
@Field final GO_VERSION = '1.16'
@Field final HELM_VERSION = '2.14.3'
@Field final JDK_VERSION = '11'
@Field final PYTHON_VERSION = '3'

@Field private final String DOCKER_CERT_PATH = '/certs/client'
@Field private final String DOCKER_CONFIG = '.docker'

@Field private retention = new OnFailure()
@Field private builder
@Field private usePrimaryBuilder

def go(Map config = [:], Closure body) {
  podTemplate(namespace: 'cicd', podRetention: retention, containers: [
      containerTemplate(
          name: builderName(),
          image: "$DOCKER_REGISTRY/golang-builder:${config.version ?: GO_VERSION}",
          ttyEnabled: true,
          alwaysPullImage: true,
          command: 'cat',
          envVars: dockerEnv()
      )
  ]) {
    docker { helm body }
  }
}

def java(Map config = [:], Closure body) {
  podTemplate(namespace: 'cicd', podRetention: retention, containers: [
      containerTemplate(
          name: builderName(),
          image: "$DOCKER_REGISTRY/java-builder:jdk${config.version ?: JDK_VERSION}",
          ttyEnabled: true,
          alwaysPullImage: true,
          command: 'cat',
          envVars: dockerEnv()
      )
  ]) {
    docker { helm body }
  }
}

def python(Map config = [:], Closure body) {
  podTemplate(namespace: 'cicd', podRetention: retention, containers: [
      containerTemplate(
          name: builderName(),
          image: "$DOCKER_REGISTRY/python-builder:${config.version ?: PYTHON_VERSION}",
          ttyEnabled: true,
          alwaysPullImage: true,
          command: 'cat',
          envVars: dockerEnv()
      )
  ]) {
    docker { helm body }
  }
}

def simple(Closure body) {
  podTemplate(namespace: 'cicd', podRetention: retention, containers: [
      containerTemplate(
          name: builderName(),
          image: "$DOCKER_REGISTRY/simple-builder",
          ttyEnabled: true,
          alwaysPullImage: true,
          command: 'cat',
          envVars: dockerEnv()
      )
  ]) {
    docker { helm body }
  }
}

def docker(Map config = [:], Closure body) {
  podTemplate(namespace: 'cicd', podRetention: retention, containers: [
      containerTemplate(
          name: 'docker',
          image: "docker:${config.version ?: DOCKER_VERSION}-dind",
          ttyEnabled: true,
          privileged: true,
          envVars: [ envVar(key: 'DOCKER_CONFIG', value: DOCKER_CONFIG) ],
      )
  ], volumes: [
      emptyDirVolume(mountPath: '/cache'),
      emptyDirVolume(mountPath: DOCKER_CERT_PATH),
      emptyDirVolume(mountPath: '/var/lib/docker'),
      hostPathVolume(hostPath: '/var/run/dind', mountPath: '/var/run/dind')
  ]) {
    body()
  }
}

def gcloud(Map config = [:], Closure body) {
  final image = "gcr.io/google.com/cloudsdktool/cloud-sdk:${config.version ?: GCLOUD_VERSION}-alpine"
  podTemplate(namespace: 'cicd', podRetention: retention, containers: [
      containerTemplate(name: 'gcloud', image: image, ttyEnabled: true, command: 'cat')
  ], volumes: [
    emptyDirVolume(mountPath: '/var/secrets/google')
  ]) {
    body()
  }
}

def helm(Map config = [:], Closure body) {
  final image = "lachlanevenson/k8s-helm:v${config.version ?: HELM_VERSION}"
  podTemplate(namespace: 'cicd', podRetention: retention, containers: [
      containerTemplate(name: 'helm', image: image, ttyEnabled: true, command: 'cat')
  ]) {
    body()
  }
}

def withRetention(boolean retain) {
  retention = retain ? new Always() : new Never()
  this
}

def builder(name) {
  usePrimaryBuilder && builder == name ? 'builder' : name
}

private def builderName() {
  final name = StackTraceUtils.sanitize(new Throwable()).stackTrace[1].methodName

  if (usePrimaryBuilder) {
    return name
  }

  usePrimaryBuilder = true
  builder = name
  'builder'
}

private List dockerEnv() {
  [
      envVar(key: 'DOCKER_CERT_PATH', value: DOCKER_CERT_PATH),
      envVar(key: 'DOCKER_CONFIG', value: DOCKER_CONFIG),
      envVar(key: 'DOCKER_HOST', value: 'tcp://localhost:2376'),
      envVar(key: 'DOCKER_TLS_VERIFY', value: '1')
  ]
}