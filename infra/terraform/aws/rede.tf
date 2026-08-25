resource "aws_vpc" "principal" {
  cidr_block           = var.cidr_vpc
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = { Name = "${local.prefixo}-vpc" }
}

resource "aws_internet_gateway" "principal" {
  vpc_id = aws_vpc.principal.id
  tags   = { Name = "${local.prefixo}-igw" }
}

resource "aws_subnet" "publica" {
  for_each = { for indice, zona in local.zonas : zona => indice }

  vpc_id                  = aws_vpc.principal.id
  availability_zone       = each.key
  cidr_block              = cidrsubnet(var.cidr_vpc, 4, each.value)
  map_public_ip_on_launch = false

  tags = {
    Name                     = "${local.prefixo}-publica-${each.key}"
    "kubernetes.io/role/elb" = "1"
  }
}

resource "aws_subnet" "privada" {
  for_each = { for indice, zona in local.zonas : zona => indice }

  vpc_id            = aws_vpc.principal.id
  availability_zone = each.key
  cidr_block        = cidrsubnet(var.cidr_vpc, 4, each.value + 3)

  tags = {
    Name                              = "${local.prefixo}-privada-${each.key}"
    "kubernetes.io/role/internal-elb" = "1"
    "karpenter.sh/discovery"          = local.prefixo
  }
}

resource "aws_eip" "nat" {
  domain = "vpc"
  tags   = { Name = "${local.prefixo}-nat" }
}

resource "aws_nat_gateway" "principal" {
  allocation_id = aws_eip.nat.id
  subnet_id     = values(aws_subnet.publica)[0].id
  depends_on    = [aws_internet_gateway.principal]

  tags = { Name = "${local.prefixo}-nat" }
}

resource "aws_route_table" "publica" {
  vpc_id = aws_vpc.principal.id
  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.principal.id
  }
  tags = { Name = "${local.prefixo}-rotas-publicas" }
}

resource "aws_route_table_association" "publica" {
  for_each       = aws_subnet.publica
  subnet_id      = each.value.id
  route_table_id = aws_route_table.publica.id
}

resource "aws_route_table" "privada" {
  vpc_id = aws_vpc.principal.id
  route {
    cidr_block     = "0.0.0.0/0"
    nat_gateway_id = aws_nat_gateway.principal.id
  }
  tags = { Name = "${local.prefixo}-rotas-privadas" }
}

resource "aws_route_table_association" "privada" {
  for_each       = aws_subnet.privada
  subnet_id      = each.value.id
  route_table_id = aws_route_table.privada.id
}

resource "aws_security_group" "aplicacao" {
  name        = "${local.prefixo}-aplicacao"
  description = "Comunicacao dos nos e pods da Orquestra de Pagamentos"
  vpc_id      = aws_vpc.principal.id

  tags = {
    "karpenter.sh/discovery" = local.prefixo
  }

  dynamic "egress" {
    for_each = length(var.cidrs_saida_https) == 0 ? [] : [1]
    content {
      description = "HTTPS por proxies ou firewalls de saida aprovados"
      from_port   = 443
      to_port     = 443
      protocol    = "tcp"
      cidr_blocks = var.cidrs_saida_https
    }
  }

  egress {
    description = "Comunicacao TCP interna entre nos, pods e servicos"
    from_port   = 0
    to_port     = 65535
    protocol    = "tcp"
    self        = true
  }

  egress {
    description = "Resolucao DNS UDP dentro da VPC"
    from_port   = 53
    to_port     = 53
    protocol    = "udp"
    cidr_blocks = [var.cidr_vpc]
  }

  egress {
    description = "Resolucao DNS TCP dentro da VPC"
    from_port   = 53
    to_port     = 53
    protocol    = "tcp"
    cidr_blocks = [var.cidr_vpc]
  }

  egress {
    description = "PostgreSQL privado"
    from_port   = 5432
    to_port     = 5432
    protocol    = "tcp"
    cidr_blocks = [var.cidr_vpc]
  }

  egress {
    description = "Redis TLS privado"
    from_port   = 6379
    to_port     = 6379
    protocol    = "tcp"
    cidr_blocks = [var.cidr_vpc]
  }

  egress {
    description = "MSK IAM privado"
    from_port   = 9098
    to_port     = 9098
    protocol    = "tcp"
    cidr_blocks = [var.cidr_vpc]
  }

  lifecycle {
    precondition {
      condition     = lower(var.ambiente) != "producao" || length(var.cidrs_saida_https) > 0
      error_message = "Producao exige ao menos um proxy ou firewall privado em cidrs_saida_https."
    }
  }
}

resource "aws_security_group" "dados" {
  name        = "${local.prefixo}-dados"
  description = "Acesso privado do EKS aos servicos de dados"
  vpc_id      = aws_vpc.principal.id

  ingress {
    description = "PostgreSQL"
    from_port   = 5432
    to_port     = 5432
    protocol    = "tcp"
    security_groups = concat(
      [aws_security_group.aplicacao.id],
      var.habilitar_proxy_banco ? [aws_security_group.proxy_banco[0].id] : []
    )
  }
  ingress {
    description     = "Redis TLS"
    from_port       = 6379
    to_port         = 6379
    protocol        = "tcp"
    security_groups = [aws_security_group.aplicacao.id]
  }
  ingress {
    description     = "MSK IAM"
    from_port       = 9098
    to_port         = 9098
    protocol        = "tcp"
    security_groups = [aws_security_group.aplicacao.id]
  }
}
