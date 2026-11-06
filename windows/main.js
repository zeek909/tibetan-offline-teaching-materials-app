const { app, BrowserWindow, protocol, net, shell } = require('electron');
const path = require('path');
const { pathToFileURL } = require('url');
protocol.registerSchemesAsPrivileged([{ scheme: 'app', privileges: { standard: true, secure: true, supportFetchAPI: true, stream: true } }]);
app.whenReady().then(() => {
  protocol.handle('app', req => {
    let p = decodeURIComponent(new URL(req.url).pathname);
    if (p === '/') p = '/index.html';
    return net.fetch(pathToFileURL(path.join(__dirname, 'www', p)).toString());
  });
  const w = new BrowserWindow({ width: 1280, height: 820, backgroundColor: '#f6efe3', icon: path.join(__dirname, 'icon.png'), autoHideMenuBar: true });
  w.webContents.setWindowOpenHandler(({ url }) => { shell.openExternal(url); return { action: 'deny' }; });
  w.loadURL('app://local/index.html');
});
app.on('window-all-closed', () => app.quit());
