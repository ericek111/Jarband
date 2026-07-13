export function channelAccentColor(channel: string) {
  let hash = 0;
  for (let i = 0; i < channel.length; i++) {
    hash = ((hash << 5) - hash + channel.charCodeAt(i)) | 0;
  }
  const hue = ((hash % 360) + 360) % 360;
  return `hsl(${hue} 76% 62%)`;
}
