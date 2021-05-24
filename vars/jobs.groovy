@groovy.transform.Field
private def task = new Task(this)

Map getProps() {
  task.props
}

def exec(String cmd = '') {
  // Exec containerizes before analyzing, as some integration tests may
  // execute against an image in the container registry.
  fetch().build(cmd: cmd, force: false).containerize().analyze().deploy()
}

// Even within shared libraries metaprogramming is severely limited.
// Unfortunately that means each method from Task must be plumbed through
// this class, so that it can be accessed via the job singleton.

def fetch(Map config = [:]) {
  use task.fetch(config)
}

def build(Map config = [:]) {
  use task.build(config)
}

def analyze(Map config = [:]) {
  use task.analyze(config)
}

def containerize(Map config = [:]) {
  use task.containerize(config)
}

def deploy(Map config = [:]) {
  use task.deploy(config)
}

def testIntegration(Map config = [:]) {
  use task.testIntegration(config)
}

def deployToKubernetes(Map config = [:]) {
  use task.deployToKubernetes(config)
}

def deployToDataflow(Map config = [:]) {
  use task.deployToDataflow(config)
}

def deployFromConfig(Map config = [:]) {
  use task.deployFromConfig(config)
}

private Task use(Task task) {
  this.@task = task
  task
}