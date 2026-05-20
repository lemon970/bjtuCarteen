import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'

import InputPage from './InputPage'
import { DEFAULT_FORM } from '../constants'

function renderInputPage(overrides = {}) {
  const onFieldChange = vi.fn()
  const onRunModeChange = vi.fn()
  const props = {
    form: DEFAULT_FORM,
    loading: false,
    message: '',
    scenarios: [],
    selectedScenarioIds: [],
    onFieldChange,
    onLoadScenario: vi.fn(),
    onToggleScenario: vi.fn(),
    onRunScenarioBatch: vi.fn(),
    onReset: vi.fn(),
    onRun: vi.fn((e) => e?.preventDefault?.()),
    onLoadLatest: vi.fn(),
    onFileUpload: vi.fn(),
    runMode: 'auto',
    onRunModeChange,
    ...overrides
  }
  return { ...render(<InputPage {...props} />), onFieldChange, onRunModeChange, props }
}

describe('InputPage 运行模式 select', () => {
  it('渲染 3 个 option:auto / sync / async', () => {
    renderInputPage()
    const select = screen.getByTestId('run-mode-select')
    expect(select).toBeInTheDocument()
    const optionValues = Array.from(select.querySelectorAll('option')).map((o) => o.value)
    expect(optionValues).toEqual(['auto', 'sync', 'async'])
  })

  it('受控 value 反映 props.runMode', () => {
    renderInputPage({ runMode: 'async' })
    const select = screen.getByTestId('run-mode-select')
    expect(select.value).toBe('async')
  })

  it('切换 select 触发 onRunModeChange,值与 option value 严格一致', () => {
    const { onRunModeChange } = renderInputPage({ runMode: 'auto' })
    const select = screen.getByTestId('run-mode-select')
    fireEvent.change(select, { target: { value: 'sync' } })
    expect(onRunModeChange).toHaveBeenCalledWith('sync')
    fireEvent.change(select, { target: { value: 'async' } })
    expect(onRunModeChange).toHaveBeenCalledWith('async')
  })
})
