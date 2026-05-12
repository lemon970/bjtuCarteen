import { normalizePoint } from '../utils/simulation'

function FlowAnimation({ point }) {
  const normalized = point ? normalizePoint(point, 0) : null
  const queue = normalized?.queue ?? 0
  const arrived = normalized?.arrived ?? 0
  const particles = Array.from({ length: Math.min(28, Math.max(8, Math.ceil(queue / 2) + 8)) })

  return (
    <div className="flow-animation">
      <div className="flow-stage">
        <div className="flow-node gate">入口</div>
        <div className="flow-node window">窗口</div>
        <div className="flow-node seat">座位</div>
        {particles.map((_, index) => (
          <i
            key={index}
            className="flow-dot"
            style={{
              animationDelay: `${(index % 12) * 0.18}s`,
              top: `${26 + (index % 5) * 8}%`
            }}
          />
        ))}
      </div>
      <div className="flow-caption">
        <span>累计到达 {arrived}</span>
        <span>当前排队 {queue}</span>
      </div>
    </div>
  )
}

export default FlowAnimation
