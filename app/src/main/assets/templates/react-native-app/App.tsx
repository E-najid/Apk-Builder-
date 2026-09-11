/**
 * {{APP_NAME}} — built with APK Builder.
 * Edit this file, push, and the workflow builds a fresh APK.
 */
import { StyleSheet, Text, View } from 'react-native';

const appName = '{{APP_NAME}}';

export default function App() {
  return (
    <View style={styles.container}>
      <Text style={styles.title}>{appName}</Text>
      <Text style={styles.subtitle}>Edit App.tsx and build again</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: '#ffffff',
  },
  title: {
    fontSize: 28,
    fontWeight: '700',
    color: '#111111',
  },
  subtitle: {
    marginTop: 8,
    fontSize: 15,
    color: '#666666',
  },
});
