import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'com.gcn.voice',
  appName: 'GCN Voice',
  webDir: 'dist',
  android: {
    allowMixedContent: true,
  },
  server: {
    // For dev: uncomment and set to your PC's LAN IP so the app on the phone
    // loads from the Vite dev server (hot reload). Comment out for production builds.
    // url: 'https://192.168.1.196:5173',
    cleartext: true,
    androidScheme: 'https',
  },
};

export default config;
