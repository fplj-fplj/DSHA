# The binary is cross-built and verified against the signed SHA256SUMS before
# docker build. scratch is an empty built-in base; no mutable registry tag.
FROM scratch
COPY --chmod=0555 dsha-relay /dsha-relay
USER 65532:65532
ENTRYPOINT ["/dsha-relay"]
CMD ["-config", "/etc/dsha-relay/config.json"]
