import { act, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useNearView } from './useNearView'

function Probe() {
  const { ref, near, minHeight } = useNearView<HTMLDivElement>()
  return (
    <div ref={ref} data-testid="probe">
      {near ? 'near' : 'far'} {minHeight ?? 'none'}
    </div>
  )
}

describe('useNearView', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('keepsTheLastHeightWhileAwayAndForgetsItOnResize', () => {
    let notify: IntersectionObserverCallback = () => {}
    vi.stubGlobal(
      'IntersectionObserver',
      class {
        constructor(cb: IntersectionObserverCallback) {
          notify = cb
        }
        observe() {}
        disconnect() {}
      },
    )
    render(<Probe />)
    const el = screen.getByTestId('probe')
    Object.defineProperty(el, 'offsetHeight', { value: 321 })
    const show = (isIntersecting: boolean) => act(() => notify([{ isIntersecting } as IntersectionObserverEntry], {} as IntersectionObserver))

    show(true)
    expect(el).toHaveTextContent('near none')
    show(false)
    expect(el).toHaveTextContent('far 321')
    act(() => void window.dispatchEvent(new Event('resize')))
    expect(el).toHaveTextContent('far none')
  })

  it('isNearWithoutIntersectionObserver', () => {
    vi.stubGlobal('IntersectionObserver', undefined)
    render(<Probe />)
    expect(screen.getByTestId('probe')).toHaveTextContent('near none')
  })
})
