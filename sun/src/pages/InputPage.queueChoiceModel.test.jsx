import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'

import InputPage from './InputPage'
import { DEFAULT_FORM } from '../constants'

function renderInputPage(overrides = {}) {
  const onFieldChange = vi.fn()
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
    onRunModeChange: vi.fn(),
    ...overrides
  }
  return { ...render(<InputPage {...props} />), onFieldChange, props }
}

/** 找到“队列选择模型”那个 select(高级参数 details 内的 RFC-009 卡片)。 */
function findQueueChoiceSelect() {
  // 用 label 文本定位 select(label.htmlFor 不好猜,直接用文本旁边的 select)。
  const labels = screen.getAllByText('队列选择模型')
  expect(labels.length).toBeGreaterThan(0)
  const wrapper = labels[0].closest('label')
  expect(wrapper).toBeTruthy()
  const select = wrapper.querySelector('select')
  expect(select).toBeTruthy()
  return select
}

describe('InputPage RFC-009 队列选择模型入口', () => {
  it('默认 STATIC_SPLIT,且不显示 5 个吸引力字段', () => {
    renderInputPage()
    const select = findQueueChoiceSelect()
    expect(select.value).toBe('STATIC_SPLIT')

    const optionValues = Array.from(select.querySelectorAll('option')).map((o) => o.value)
    expect(optionValues).toEqual(['STATIC_SPLIT', 'PREFERENCE_AWARE'])

    expect(screen.queryByText('热门窗口比例')).toBeNull()
    expect(screen.queryByText('冷门窗口比例')).toBeNull()
    expect(screen.queryByText('热门吸引力')).toBeNull()
    expect(screen.queryByText('普通吸引力')).toBeNull()
    expect(screen.queryByText('冷门吸引力')).toBeNull()
  })

  it('切换为 PREFERENCE_AWARE 时显示 5 个吸引力参数', () => {
    renderInputPage({
      form: { ...DEFAULT_FORM, queueChoiceModel: 'PREFERENCE_AWARE' }
    })
    expect(screen.getByText('热门窗口比例')).toBeInTheDocument()
    expect(screen.getByText('冷门窗口比例')).toBeInTheDocument()
    expect(screen.getByText('热门吸引力')).toBeInTheDocument()
    expect(screen.getByText('普通吸引力')).toBeInTheDocument()
    expect(screen.getByText('冷门吸引力')).toBeInTheDocument()
  })

  it('选择 PREFERENCE_AWARE 触发 onFieldChange("queueChoiceModel", "PREFERENCE_AWARE")', () => {
    const { onFieldChange } = renderInputPage()
    const select = findQueueChoiceSelect()
    fireEvent.change(select, { target: { value: 'PREFERENCE_AWARE' } })
    expect(onFieldChange).toHaveBeenCalledWith('queueChoiceModel', 'PREFERENCE_AWARE')
  })
})
