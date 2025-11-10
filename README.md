# The AWS Assume Role Operator

**This project has been replaced by https://github.com/wdonne/aws-assume-role.**

On AWS EKS you can let a pod assume an IAM role by associating it with a service account through either [IAM roles](https://docs.aws.amazon.com/eks/latest/userguide/iam-roles-for-service-accounts.html) or [Pod Identities](https://docs.aws.amazon.com/eks/latest/userguide/pod-identities.html), the latter being the simplest approach. This way you don't have to give such privileges to the worker nodes. Moreover, with [Karpenter](https://karpenter.sh), the creation of nodes is very dynamic. In practice you would have to give all your roles to all of your nodes and hence any pod.

For cases where using the service account is not possible, this operator provides a scenario where you let it assume all the IAM roles you need. For each role it will generate a secret with the access key ID, the secret access key and a session token. The secret is refreshed according to the duration you set, with a small overlap to avoid gaps. The generated secrets can be used by other resources that need access to AWS services.

You can create a pod identity association for the service account `aws-assume-role-controller` and the namespace you install the operator in. The role in the association should have a policy that allows the desired roles to be assumed. Those desired roles should have a trust relationship with the role in the association.

The following example generates a secret for the IAM role `my-secrets-manager-role`. Its `secretType` is `Map`, which adds the three fields individually.

```yaml
apiVersion: pincette.net/v1
kind: AWSAssumeRole
metadata:
  name: aws-assume-my-role
  namespace: default
spec:
  roleName: my-secrets-manager-role
  secretName: assume-my-secrets-manager-role
  secretType: Map
```

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: assume-my-secrets-manager-role
  namespace: default
type: Opaque
data:
  awsAccessKeyId: XXXXXXXXXX
  awsSecretAccessKey: XXXXXXXXXX
  awsSessionToken: XXXXXXXXXX
```

If you set the `secretType` field to `File` the secret will look like this:

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: assume-my-secrets-manager-role
  namespace: default
type: Opaque
data:
  credentials: |
    [default]
    aws_access_key_id=XXXXXXXXXX
    aws_secret_access_key=XXXXXXXXXX
    aws_session_token=XXXXXXXXXX    
```

Set the environment variable `AWS_SHARED_CREDENTIALS_FILE` to `/.aws/credentials`. You can then mount this in a pod as a volume at `/.aws`. The pod will see it as the file `/.aws/credentials`. This is an example:

```yaml
apiVersion: v1
kind: Pod
metadata:
  name: mypod
spec:
  containers:
    - name: mypod
      image: myimage
      env:
        - name: AWS_SHARED_CREDENTIALS_FILE
          value: "/.aws/credentials"        
      volumeMounts:
        - name: aws
          mountPath: "/.aws"
          readOnly: true
  volumes:
    - name: aws
      secret:
        secretName: assume-my-secrets-manager-role
```

## Elastic Container Registry

For every ECR repository you want to use you can generate a secret that is constraint to only that repository. The secret type should be `EcrDockerConfigJson`. As the name suggests, the secret will be of the type `kubernetes.io/dockerconfigjson`. You will need to supply the repository URL in the extra field `ecrRepositoryUrl`. The username in the generated secret will be `AWS` and the password will be an authorisation token obtained from the ECR service. This is an example:

```yaml
apiVersion: pincette.net/v1
kind: AWSAssumeRole
metadata:
  name: assume-my-ecr-role
  namespace: default
spec:
  roleName: my-repo-ecr-role
  secretName: assume-my-repo-ecr-role
  secretType: EcrDockerConfigJson
  ecrRepositoryUrl: https://<account>.dkr.ecr.<region>.amazonaws.com/v2/my-repo
```

It will generate a secret like this:

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: assume-my-repo-ecr-role
  namespace: default
data:
  .dockerconfigjson: >-
    {
      "auths":
        {
          "https://<account>.dkr.ecr.<region>.amazonaws.com/v2/my-repo":
            {
              "username": "AWS",
              "password":"XXXXXXXXXX"
            }
        }
    }
type: kubernetes.io/dockerconfigjson
```

## Installation

Assuming the presence of the AWS IAM Controller, install the operator as follows:

```bash
helm repo add pincette https://pincette.net/charts
helm repo update
helm install aws-assume-role pincette/aws-assume-role --namespace aws-assume-role --create-namespace
```
