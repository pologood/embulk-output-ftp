# FTP file output plugin for Embulk
[![Build Status](https://travis-ci.org/sakama/embulk-output-ftp.svg?branch=master)](https://travis-ci.org/sakama/embulk-output-ftp)

## Overview

* **Plugin type**: file input
* **Resume supported**: no
* **Cleanup supported**: yes

## Configuration

- **host**: FTP server address (string, required)
- **port**: FTP server port number (integer, default: `21`. `990` if `ssl` is true)
- **user**: user name to login (string, optional)
- **password**: password to login (string, default: `""`)
- **path_prefix** prefix of target files (string, required)
- **sequence_format** Format for sequence part of output files (string, default: `".%03d.%02d"`)
- **ext** e.g. "csv.gz, json.gz" (string, required)
- **passive_mode**: use passive mode (boolean, default: true)
- **ascii_mode**: use ASCII mode instead of binary mode (boolean, default: false)
- **ssl**: use FTPS (SSL encryption). (boolean, default: false)
- **ssl_verify**: verify the certification provided by the server. By default, connection fails if the server certification is not signed by one the CAs in JVM's default trusted CA list. (boolean, default: true)
- **ssl_verify_hostname**: verify server's hostname matches with provided certificate. (boolean, default: true)
- **ssl_trusted_ca_cert_file**: if the server certification is not signed by a certificate authority, set path to the X.508 certification file (pem file) of a private CA (string, optional)
- **ssl_trusted_ca_cert_data**: similar to `ssl_trusted_ca_cert_file` but embed the contents of the PEM file as a string value instead of path to a local file (string, optional)

## Example

Simple FTP:

```yaml
out:
  type: ftp
  host: ftp.example.net
  port: 21
  user: anonymous
  path_prefix: /ftp/file/path/prefix
  ext: csv
  formatter:
    type: csv
    header_line: false
  encoders:
  - {type: gzip}
```

FTPS encryption without server certificate verification:

```yaml
out:
  type: ftp
  host: ftp.example.net
  port: 21
  user: anonymous
  password: "mypassword"

  ssl: true
  ssl_verify: false

  path_prefix: /ftp/file/path/prefix
  ext: csv
```

FTPS encryption with server certificate verification:

```yaml
out:
  type: ftp
  host: ftp.example.net
  port: 21
  user: anonymous
  password: "mypassword"

  ssl: true
  ssl_verify: true

  ssl_verify_hostname: false   # to disable server hostname verification (optional)

  # if the server use self-signed certificate, or set path to the pem file (optional)
  ssl_trusted_ca_cert_file: /path/to/ca_cert.pem

  # or embed contents of the pem file here (optional)
  ssl_trusted_ca_cert_data: |
      -----BEGIN CERTIFICATE-----
      MIIFV...
      ...
      ...
      -----END CERTIFICATE-----

  path_prefix: /ftp/file/path/prefix
    ext: csv
```


## Build

```
$ ./gradlew gem  # -t to watch change of files and rebuild continuously
```

## Acknowledgement

This program is forked from [embulk-input-ftp](https://github.com/embulk/embulk-input-ftp) and originally written by @frsyuki, modified by @sakama.