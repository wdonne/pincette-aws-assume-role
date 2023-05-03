# The AWS Assume Role Operator

On AWS EKS you can let a pod assume an IAM role by associating it with a [service account](https://docs.aws.amazon.com/eks/latest/userguide/iam-roles-for-service-accounts.html). This way you don't have to give such privileges to the worker nodes. Moreover, with [Karpenter](https://karpenter.sh), the creation of nodes is very dynamic. In practice you would have to give all your roles to all of your nodes and hence any pod.

If you use the [AWS IAM Controller](https://aws-controllers-k8s.github.io/community/reference/iam/v1alpha1/role/), a role would look like this:

```yaml
apiVersion: iam.services.k8s.aws/v1alpha1
kind: Role
metadata:
  name: my-role
  namespace: my-namespace
spec:
  name: aws-my-role
  description: My IAM role
  policyRefs:
    - from:
        name: aws-my-policy
  assumeRolePolicyDocument: |
    {
      "Version": "2012-10-17",
      "Statement": [
        {
          "Sid": "",
          "Effect": "Allow",
          "Principal": {
            "Federated":
              "arn:aws:iam::<account>:oidc-provider/oidc.eks.eu-central-1.amazonaws.com/id/<OIDC ID>"
          },
          "Action": "sts:AssumeRoleWithWebIdentity",
          "Condition": {
            "StringEquals": {
              "oidc.eks.<region>.amazonaws.com/id/<OIDC ID>:sub":
                "system:serviceaccount:my-namespace:my-service-account"
            }
          }
        }
      ]
    }
```

The example connects the service account `my-service-account` to the IAM role `aws-my-role`, which refers to the policy `aws-my-policy`. The service account would then be like this:

```yaml
apiVersion: v1
kind: ServiceAccount
metadata:
  name: my-service-account
  namespace: my-namespace
  annotations:
    eks.amazonaws.com/role-arn: arn:aws:iam::<account>:role/aws-my-role
```

You would have to do this for every IAM role you need, which is cumbersome. It will also only work on EKS. The internal OIDC provider is not available in another type of Kubernetes cluster. There is an [alternative](https://github.com/aws/amazon-eks-pod-identity-webhook/blob/master/SELF_HOSTED_SETUP.md) with certificates, but that is even more cumbersome.

This operator provides a scenario where you let it assume all the IAM roles you need. For each role it will generate a secret with the access key ID, the secret access key and a session token. The secret is refreshed according to the duration you set, with a small overlap to avoid gaps. The generated secrets can be used by other resources that need access to AWS services.

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

You can mount this in a pod as a volume at `$HOME/.aws`. The pod will see it as the file `$HOME/.aws/credentials`. This is an example:

```yaml
apiVersion: v1
kind: Pod
metadata:
  name: mypod
spec:
  containers:
    - name: mypod
      image: myimage
      volumeMounts:
        - name: aws
          mountPath: "$HOME/.aws"
          readOnly: true
  volumes:
    - name: aws
      secret:
        secretName: assume-my-secrets-manager-role
```

Assuming the presence of the AWS IAM Controller, install the operator as follows:

```
helm repo add pincette.net https://pincette.net/charts
helm install aws-assume-role pincette.net/aws-assume-role
```

Use a values file like this:

```yaml
accountId: "<account>"
oidcProviderId: <OIDC ID>
region: <region>
rolesToAssume:
  - "arn:aws:iam::<account>:role/*-my-roles"
```

You can also set the fields `nodeSelector` and `securityContext`.

In every IAM role you create you should set the IAM role of the operator in the thrust relationship. This role is `arn:aws:iam::<account>:role/aws-assume-role-role`. So, you have to add the following to the IAM role:

```yaml
assumeRolePolicyDocument: |
  {
    "Version": "2012-10-17",
    "Statement": [
      {
        "Sid": "",
        "Effect": "Allow",
        "Principal": {
          "AWS": "arn:aws:iam::<account>:role/aws-assume-role-role"
        },
        "Action": "sts:AssumeRole"
      }
    ]
  }
```    

To shield you from that you can also use the [Value Injector operator](https://github.com/wdonne/pincette-value-injector) and inject the field from the generated config map `aws-assume-role-policy-document` into your roles like this:

```yaml
apiVersion: pincette.net/v1
kind: ValueInjector
metadata:
  name: trust-policy-injector
spec:
  from:
    name: aws-assume-role-policy-document
    namespace: aws-assume-role
    kind: ConfigMap
  to:
    name: my-role
    namespace: my-namespace
    kind: Role
    apiVersion: iam.services.k8s.aws/v1alpha1
  pipeline:
    - $set:
        to.spec.assumeRolePolicyDocument: $from.data.assumeRolePolicyDocument
```

The Value Injector can also help with the scenario where you use the EKS cluster as a management cluster and inject the assumed roles into workload clusters that are not EKS clusters.