import { expect, test } from '@playwright/test'

/**
 * P5 的部署链回归：nginx 侧的边界行为（后端用例与前端单测都覆盖不到）。
 *
 * <p>为什么放在 e2e：这两条判据都发生在 **nginx**（>20m 在读完 body 前就被拒、版本号是 nginx 的
 * `server_tokens`），既不经过 Spring 的 GlobalExceptionHandler，也不在 Vitest 的范围内。
 */
test.describe('P5 部署链边界', () => {
  test('/api >20m 上传：nginx 返回 413 + code 2030（不是 HTML）', async ({ request }) => {
    const response = await request.post('/api/tasks/1/import', {
      multipart: {
        file: {
          name: 'too-big.bin',
          mimeType: 'application/octet-stream',
          buffer: Buffer.alloc(21 * 1024 * 1024),
        },
      },
    })
    expect(response.status(), '应由 nginx 的 client_max_body_size 拒绝').toBe(413)
    await expect(response.json()).resolves.toMatchObject({ code: 2030 })
  })

  test('/ 的 Server 头不暴露 nginx 版本', async ({ request }) => {
    const response = await request.get('/')
    const server = response.headers()['server'] ?? ''
    expect(server.toLowerCase()).toBe('nginx')
    expect(server).not.toMatch(/nginx\/\d/i)
  })
})
