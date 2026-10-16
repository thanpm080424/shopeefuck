import 'dart:convert';
import 'package:http/http.dart' as http;

class TimeManager {
  static Future<int> getCurrentUnixTime() async {
    try {
      final response = await http.get(Uri.parse('http://worldtimeapi.org/api/timezone/Etc/UTC')).timeout(const Duration(seconds: 5));
      if (response.statusCode == 200) {
        final data = json.decode(response.body);
        return data['unixtime'] as int;
      }
    } catch (e) {
      // ignore
    }
    
    // Fallback to local time if offline
    return DateTime.now().millisecondsSinceEpoch ~/ 1000;
  }
}
