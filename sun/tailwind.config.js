/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{js,jsx,ts,tsx}'],
  theme: {
    extend: {
      colors: {
        bjtu: {
          50: '#eef2ff',
          100: '#e0e7ff',
          200: '#c7d2fe',
          400: '#6d6ff5',
          500: '#3f51d4',
          600: '#1e40af',
          700: '#1e3a8a',
          800: '#172554'
        },
        accent: {
          amber: '#f59e0b',
          amberSoft: '#fde68a',
          teal: '#0d9488'
        },
        semantic: {
          success: '#15803d',
          successSoft: '#dcfce7',
          warning: '#b45309',
          warningSoft: '#fef3c7',
          critical: '#b91c1c',
          criticalSoft: '#fee2e2'
        },
        canvas: {
          base: '#f5f7fb',
          surface: '#ffffff',
          subtle: '#eef2ff',
          border: '#e2e8f0'
        }
      },
      fontFamily: {
        sans: ['"Inter"', '"Noto Sans SC"', 'system-ui', '-apple-system', 'sans-serif'],
        display: ['"Inter"', '"Noto Sans SC"', 'system-ui', 'sans-serif'],
        numeric: ['"JetBrains Mono"', '"SF Mono"', 'ui-monospace', 'monospace']
      },
      boxShadow: {
        card: '0 12px 32px -16px rgba(30, 64, 175, 0.18)',
        cardLg: '0 24px 48px -24px rgba(30, 64, 175, 0.25)',
        pill: '0 4px 12px -6px rgba(30, 64, 175, 0.3)'
      },
      borderRadius: {
        '2xl': '1.25rem',
        '3xl': '1.75rem'
      }
    }
  },
  plugins: [require('@tailwindcss/forms')]
}
