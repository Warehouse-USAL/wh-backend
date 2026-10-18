import {
  Activity,
  ArrowRight,
  Box,
  ChartColumn,
  ClipboardList,
  Clock,
  Database,
  File,
  Globe,
  LayoutGrid,
  type LucideIcon,
  Package,
  Radio,
  RefreshCw,
  Server,
  Spline,
  Text,
  Truck,
  User,
  Waypoints,
  Warehouse,
} from 'lucide-react'

export interface NavItem {
  label: string
  path: string
  icon: LucideIcon
}

export interface NavSection {
  section: string
  /** The first group sits under the brand without a heading, as in the mockups. */
  hideLabel?: boolean
  items: NavItem[]
}

const item = (label: string, path: string, icon: LucideIcon): NavItem => ({ label, path, icon })

export const NAV: NavSection[] = [
  { section: 'Overview', hideLabel: true, items: [item('Overview', '/overview', LayoutGrid)] },
  {
    section: 'Monitoring',
    items: [
      item('Users and auth', '/monitoring/users', User),
      item('Products', '/monitoring/products', Package),
      item('Orders', '/monitoring/orders', ClipboardList),
      item('Vehicles', '/monitoring/vehicles', Truck),
      item('Warehouse', '/monitoring/warehouse', Warehouse),
      item('Restock', '/monitoring/restock', RefreshCw),
      item('Files', '/monitoring/files', File),
      item('WebSockets', '/monitoring/websockets', Activity),
      item('Metrics and query', '/monitoring/metrics', ChartColumn),
      item('Infra callbacks', '/monitoring/callbacks', ArrowRight),
    ],
  },
  {
    section: 'Infrastructure',
    items: [
      item('Backend', '/infra/backend', Server),
      item('MongoDB', '/infra/mongodb', Database),
      item('Bridge', '/infra/bridge', Waypoints),
      item('Kafka', '/infra/kafka', Spline),
      item('MinIO', '/infra/minio', Box),
      item('Restock cron', '/infra/restock-cron', Clock),
      item('Edge (prod)', '/infra/edge', Globe),
      item('Telemetry', '/infra/telemetry', Radio),
      item('Logs', '/infra/logs', Text),
    ],
  },
  {
    section: 'Domains',
    items: [
      item('Users', '/domains/users', User),
      item('Products', '/domains/products', Package),
      item('Orders', '/domains/orders', ClipboardList),
      item('Vehicles', '/domains/vehicles', Truck),
      item('Warehouse', '/domains/warehouse', Warehouse),
      item('Restock', '/domains/restock', RefreshCw),
      item('Files', '/domains/files', File),
    ],
  },
  {
    section: 'Dev tools',
    items: [
      item('mongo-express', '/dev/mongo-express', Database),
      item('redpanda-console', '/dev/redpanda-console', Spline),
    ],
  },
]
