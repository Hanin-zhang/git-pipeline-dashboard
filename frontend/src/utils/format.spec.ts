import { describe, expect, it } from 'vitest'
import {
  formatDateTime,
  formatDuration,
  formatDurationShort,
  formatTime,
  percent,
  shortSha,
  shortenMessage,
  statusMeta,
  stepDuration,
  toneLabel,
  toneOf,
} from './format'

describe('formatDuration', () => {
  it('空值返回占位符而不是 0 秒', () => {
    expect(formatDuration(undefined)).toBe('—')
    expect(formatDuration(null)).toBe('—')
    expect(formatDuration(0)).toBe('—')
    expect(formatDuration(-1)).toBe('—')
  })

  it('不足一分钟只显示秒', () => {
    expect(formatDuration(30_000)).toBe('30秒')
    expect(formatDuration(1_000)).toBe('1秒')
  })

  it('分钟级补零到两位秒', () => {
    expect(formatDuration(65_000)).toBe('1分05秒')
    expect(formatDuration(600_000)).toBe('10分00秒')
  })

  it('超过一小时显示时分，不再显示秒', () => {
    expect(formatDuration(3_600_000)).toBe('1时0分')
    expect(formatDuration(5_400_000)).toBe('1时30分')
  })
})

describe('formatDurationShort', () => {
  it('空值返回占位符', () => {
    expect(formatDurationShort(null)).toBe('—')
    expect(formatDurationShort(0)).toBe('—')
  })

  it('按秒/分/时三档压缩展示', () => {
    expect(formatDurationShort(45_000)).toBe('45s')
    expect(formatDurationShort(90_000)).toBe('1m30s')
    expect(formatDurationShort(3_600_000)).toBe('1h00m')
  })
})

describe('shortSha / shortenMessage', () => {
  it('SHA 截取 7 位，和 GitHub 网页一致', () => {
    expect(shortSha('abcdef1234567890')).toBe('abcdef1')
    expect(shortSha(null)).toBe('—')
  })

  it('提交信息为空时给出可读占位', () => {
    expect(shortenMessage(null)).toBe('无提交信息')
    expect(shortenMessage('')).toBe('无提交信息')
  })

  it('超长提交信息截断并加省略号', () => {
    const long = 'x'.repeat(100)
    expect(shortenMessage(long)).toHaveLength(61)
    expect(shortenMessage(long).endsWith('…')).toBe(true)
    expect(shortenMessage('short')).toBe('short')
    expect(shortenMessage('abcdef', 3)).toBe('abc…')
  })
})

describe('statusMeta', () => {
  it('运行中与排队中分开显示', () => {
    expect(statusMeta('in_progress', null)).toMatchObject({ label: '运行中', tone: 'running' })
    expect(statusMeta('queued', null)).toMatchObject({ label: '排队中', tone: 'running' })
    expect(statusMeta('requested', null)).toMatchObject({ label: '排队中' })
    expect(statusMeta('waiting', null)).toMatchObject({ label: '排队中' })
  })

  it('各类 conclusion 都有中文标签与语义色', () => {
    expect(statusMeta('completed', 'success')).toMatchObject({ label: '成功', tone: 'success' })
    expect(statusMeta('completed', 'failure')).toMatchObject({ label: '失败', tone: 'failure' })
    expect(statusMeta('completed', 'timed_out')).toMatchObject({ label: '超时', tone: 'failure' })
    expect(statusMeta('completed', 'startup_failure')).toMatchObject({ label: '启动失败', tone: 'failure' })
    expect(statusMeta('completed', 'cancelled')).toMatchObject({ label: '已取消', tone: 'cancelled' })
    expect(statusMeta('completed', 'skipped')).toMatchObject({ label: '已跳过', tone: 'cancelled' })
  })

  it('状态优先于结论：运行中的运行不会被误标成已完成', () => {
    expect(statusMeta('in_progress', 'success').label).toBe('运行中')
  })

  it('未知状态兜底为中性，不会显示 undefined', () => {
    expect(statusMeta(null, null)).toMatchObject({ label: '未知', tone: 'neutral' })
    expect(statusMeta('completed', null).label).toBe('已完成')
    expect(statusMeta(undefined, undefined).className).toBe('status-neutral')
  })
})

describe('toneOf', () => {
  it('conclusion 优先决定色调', () => {
    expect(toneOf('completed', 'success')).toBe('success')
    expect(toneOf('completed', 'failure')).toBe('failure')
    expect(toneOf('completed', 'timed_out')).toBe('failure')
    expect(toneOf('completed', 'startup_failure')).toBe('failure')
    expect(toneOf('completed', 'cancelled')).toBe('cancelled')
    expect(toneOf('completed', 'skipped')).toBe('skipped')
    expect(toneOf('completed', 'neutral')).toBe('skipped')
  })

  it('未结束时按 status 判断运行中/排队中', () => {
    expect(toneOf('in_progress', null)).toBe('running')
    expect(toneOf('queued', null)).toBe('queued')
    expect(toneOf('requested', null)).toBe('queued')
    expect(toneOf('waiting', null)).toBe('queued')
    expect(toneOf('pending', null)).toBe('queued')
  })

  it('都没有时返回中性', () => {
    expect(toneOf(null, null)).toBe('neutral')
    expect(toneOf(undefined, undefined)).toBe('neutral')
  })
})

describe('toneLabel', () => {
  it('每种色调都有中文标签', () => {
    expect(toneLabel('success')).toBe('成功')
    expect(toneLabel('failure')).toBe('失败')
    expect(toneLabel('running')).toBe('运行中')
    expect(toneLabel('queued')).toBe('排队中')
    expect(toneLabel('skipped')).toBe('已跳过')
    expect(toneLabel('cancelled')).toBe('已取消')
    expect(toneLabel('neutral')).toBe('未开始')
  })
})

describe('percent', () => {
  it('保留一位小数并带百分号', () => {
    expect(percent(87.5)).toBe('87.5%')
    expect(percent(0)).toBe('0.0%')
    expect(percent(66.66, 1)).toBe('66.7%')
    expect(percent(66.66, 0)).toBe('67%')
  })

  it('空值返回占位符', () => {
    expect(percent(null)).toBe('—')
    expect(percent(undefined)).toBe('—')
  })
})

describe('时间展示', () => {
  it('日期时间格式固定为 MM-DD HH:mm:ss', () => {
    expect(formatDateTime('2026-01-01 10:00:00')).toBe('01-01 10:00:00')
    expect(formatTime('2026-01-01 10:00:00')).toBe('10:00:00')
  })

  it('空值返回占位符', () => {
    expect(formatDateTime(null)).toBe('—')
    expect(formatTime(undefined)).toBe('—')
  })
})

describe('stepDuration', () => {
  it('起止时间齐全时返回毫秒差', () => {
    expect(stepDuration({ startedAt: '2026-01-01T10:00:00Z', completedAt: '2026-01-01T10:00:05Z' })).toBe(5000)
  })

  it('缺少任一时间时返回 undefined（前端展示为进行中）', () => {
    expect(stepDuration({ startedAt: '2026-01-01T10:00:00Z' })).toBeUndefined()
    expect(stepDuration({})).toBeUndefined()
  })

  it('无法解析的时间不会产生 NaN', () => {
    expect(stepDuration({ startedAt: 'oops', completedAt: '2026-01-01T10:00:00Z' })).toBeUndefined()
  })

  it('结束早于开始时夹到 0', () => {
    expect(stepDuration({ startedAt: '2026-01-01T10:00:05Z', completedAt: '2026-01-01T10:00:00Z' })).toBe(0)
  })
})
