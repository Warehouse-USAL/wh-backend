import { useEffect, useRef, useState } from 'react'

/**
 * Whether the element is in or near (400 px) the scrolled `main`, so a long page mounts, and
 * subscribes, only the groups on screen. Away from the view it reports the element's last height,
 * to keep as `minHeight` so nothing jumps. Without IntersectionObserver everything is near.
 */
export function useNearView<T extends HTMLElement>() {
  const ref = useRef<T>(null)
  const [near, setNear] = useState(typeof IntersectionObserver === 'undefined')
  const [minHeight, setMinHeight] = useState<number>()

  useEffect(() => {
    const el = ref.current
    if (!el || typeof IntersectionObserver === 'undefined') return
    const observer = new IntersectionObserver(
      ([entry]) => {
        if (!entry.isIntersecting) setMinHeight(el.offsetHeight)
        setNear(entry.isIntersecting)
      },
      { root: el.closest('main'), rootMargin: '400px 0px' },
    )
    observer.observe(el)
    return () => observer.disconnect()
  }, [])

  return { ref, near, minHeight }
}
