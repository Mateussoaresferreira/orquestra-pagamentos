module "karpenter" {
  source  = "terraform-aws-modules/eks/aws//modules/karpenter"
  version = "21.24.2"

  create          = var.habilitar_karpenter
  region          = var.regiao
  cluster_name    = aws_eks_cluster.principal.name
  namespace       = "karpenter"
  service_account = "karpenter"

  create_pod_identity_association = true
  create_access_entry             = true
  enable_spot_termination         = true
  queue_managed_sse_enabled       = true

  iam_role_name                   = "${local.prefixo}-karpenter"
  iam_role_use_name_prefix        = false
  node_iam_role_name              = "${local.prefixo}-karpenter-nos"
  node_iam_role_use_name_prefix   = false
  node_iam_role_attach_cni_policy = true

  tags = var.tags

  depends_on = [aws_eks_addon.essenciais]
}
