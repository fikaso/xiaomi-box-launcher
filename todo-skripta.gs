// Lagani Launcher – kvačenje zadataka iz TV-a.
// Zalijepi u tablici "TV popis": Proširenja → Apps Script, umjesto svega što piše, pa Spremi.
// Skripta smije SAMO staviti kvačicu "gotovo" (stupac B) uz zadatak s točno tim tekstom.

function doGet(e) {
  var task = (e && e.parameter && e.parameter.done) || '';
  if (!task) return out('nema zadatka');
  var sheet = SpreadsheetApp.getActiveSpreadsheet().getSheets()[0];
  var rows = sheet.getDataRange().getValues();
  for (var i = 1; i < rows.length; i++) {              // red 0 je zaglavlje
    if (String(rows[i][0]).trim() === task.trim() && rows[i][1] !== true) {
      sheet.getRange(i + 1, 2).setValue(true);
      return out('ok');
    }
  }
  return out('nije nađeno');
}

function out(text) {
  return ContentService.createTextOutput(text).setMimeType(ContentService.MimeType.TEXT);
}
