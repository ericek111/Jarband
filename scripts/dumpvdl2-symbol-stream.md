# dumpvdl2 symbol stream input

Jarband's VDL2 path intentionally stops at 8DPSK demodulation. It sends symbols
to dumpvdl2 and leaves descrambling, transmission length, FEC/RS, HDLC
unstuffing, AVLC framing and protocol decoding in dumpvdl2.

TCP stream format:

```text
4 bytes   ASCII magic: JV2S
uint8     version = 1

Repeated symbol batches:
uint32_be frequency_hz
uint64_be unix_millis
float32_be frame_power_dbfs
float32_be noise_floor_dbfs
float32_be ppm_error
uint16_be symbol_count
uint32_be reserved
uint8[]   symbol_bits, one byte per VDL2 symbol, low 3 bits used
```

The dumpvdl2-side input should:

1. Listen on the configured TCP port.
2. Read and verify `JV2S`.
3. Keep one `vdl2_channel_t` per frequency.
4. For each symbol, call `bitstream_append_msbfirst(v->bs, &symbol, 1, BPS)`.
5. Copy the metadata into `vdl2_channel_t` before decoding so existing dumpvdl2
   output code sees frequency, timestamp, frame power, noise floor and PPM.
6. When `v->bs->end - v->bs->start >= v->requested_bits`, call `decode_vdl2_burst(v)`.
7. On `DEC_IDLE`, reset the channel decoder state the same way demod.c does after a bad burst.

That patch belongs in dumpvdl2, not Jarband, because it needs access to
`vdl2_channel_t`, `bitstream_t`, and `decode_vdl2_burst()`.
